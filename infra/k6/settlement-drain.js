import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';
import { GATEWAY_URL, authHeaders, createActiveAuction, issueToken } from './lib/api.js';

/**
 * 시나리오 3 — 경매 N개가 같은 시각에 마감될 때, 정산 파이프라인이 전부 끝내는 데 걸리는 시간.
 *
 * 경로: 스케줄러 A단계(마감) → B단계(gRPC 낙찰 확정, 틱당 settlement-batch-size건) → outbox → Kafka
 *       → payment 결제 → outbox → Kafka → auction 완료. 결제 실패 건은 차순위 승계를 한 번 더 돈다.
 *
 * 경매 구성(비율은 고정):
 *   - 정상: 입찰자 A 1명 → COMPLETED, winnerId=A
 *   - 결제 실패 → 승계: B가 먼저, A가 끝자리 9999로 나중에 입찰 → A 결제 실패 → B로 승계 → COMPLETED, winnerId=B
 *   - 유찰: 입찰 없음 → FAILED
 *
 * 환경 변수: AUCTIONS(기본 200), END_IN(준비 시작부터 마감까지 초, 기본 90)
 */

const AUCTIONS = parseInt(__ENV.AUCTIONS || '200');
const END_IN = parseInt(__ENV.END_IN || '90');
const STARTING_PRICE = 10000;
const SETUP_TAGS = { phase: 'setup' };

const timeToTerminal = new Trend('auction_time_to_terminal', true);
const drainTime = new Trend('settlement_drain_time', true);

export const options = {
    setupTimeout: `${END_IN + 60}s`,
    scenarios: {
        drain: {
            executor: 'per-vu-iterations',
            vus: 1,
            iterations: 1,
            maxDuration: '10m',
            tags: { phase: 'measure' },
        },
    },
    thresholds: {
        'checks{verify:final_state}': ['rate==1'],
    },
};

function kindOf(index) {
    const r = index % 10;
    if (r === 0) return 'no_bids';
    if (r === 1) return 'reassign';
    return 'normal';
}

function placeBid(token, auctionId, amount) {
    const res = http.post(`${GATEWAY_URL}/api/v1/bids`, JSON.stringify({ auctionId, amount }),
        { headers: authHeaders(token), tags: SETUP_TAGS });
    if (res.status !== 201) {
        throw new Error(`준비 입찰 실패 auctionId=${auctionId} amount=${amount} status=${res.status} body=${res.body}`);
    }
}

export function setup() {
    const seller = issueToken(1);
    const bidderA = { userId: 2001, token: issueToken(2001) };
    const bidderB = { userId: 2002, token: issueToken(2002) };

    // 모든 경매의 마감을 같은 절대 시각으로 맞춘다.
    const endAt = Date.now() + END_IN * 1000;
    const auctions = [];
    for (let i = 0; i < AUCTIONS; i++) {
        const endInSeconds = Math.max(1, Math.round((endAt - Date.now()) / 1000));
        const { auctionId } = createActiveAuction(seller, {
            startingPrice: STARTING_PRICE, endInSeconds, name: `k6 drain ${i}`,
        });
        const kind = kindOf(i);
        if (kind === 'normal') {
            placeBid(bidderA.token, auctionId, 20000);
        } else if (kind === 'reassign') {
            placeBid(bidderB.token, auctionId, STARTING_PRICE);
            placeBid(bidderA.token, auctionId, 19999); // payment.simulation 실패 접미사 9999
        }
        auctions.push({ auctionId, kind });
    }

    const remaining = Math.round((endAt - Date.now()) / 1000);
    if (remaining < 5) {
        throw new Error(`준비가 마감 직전까지 걸렸다(남은 ${remaining}초). END_IN을 늘리거나 AUCTIONS를 줄인다.`);
    }
    console.log(`[setup] 경매 ${AUCTIONS}개 준비 완료, 마감까지 ${remaining}초`);
    return { auctions, endAt, bidderA: bidderA.userId, bidderB: bidderB.userId };
}

function expected(kind, data) {
    if (kind === 'no_bids') return { status: 'FAILED', winnerId: null };
    if (kind === 'reassign') return { status: 'COMPLETED', winnerId: data.bidderB };
    return { status: 'COMPLETED', winnerId: data.bidderA };
}

export default function (data) {
    // 마감 시각까지 기다린다.
    const wait = (data.endAt - Date.now()) / 1000;
    if (wait > 0) sleep(wait);

    const pending = new Map(data.auctions.map((a) => [a.auctionId, a]));
    const finals = [];

    while (pending.size > 0) {
        const ids = Array.from(pending.keys());
        const responses = http.batch(ids.map((id) => ({
            method: 'GET',
            url: `${GATEWAY_URL}/api/v1/auctions/${id}`,
            params: { tags: { name: 'GET /api/v1/auctions/{id}' } },
        })));
        const now = Date.now();

        responses.forEach((res, i) => {
            if (res.status !== 200) return;
            const body = res.json();
            if (body.status === 'COMPLETED' || body.status === 'FAILED') {
                const auction = pending.get(ids[i]);
                timeToTerminal.add(now - data.endAt, { kind: auction.kind });
                finals.push({ ...auction, status: body.status, winnerId: body.winnerId });
                pending.delete(ids[i]);
            }
        });

        if (pending.size > 0) sleep(1);
    }

    drainTime.add(Date.now() - data.endAt);

    for (const f of finals) {
        const want = expected(f.kind, data);
        const ok = f.status === want.status && (f.winnerId ?? null) === want.winnerId;
        if (!ok) {
            console.warn(`[verify] 예상과 다름 auctionId=${f.auctionId} kind=${f.kind} `
                + `status=${f.status} winnerId=${f.winnerId} 예상=${JSON.stringify(want)}`);
        }
        check(null, { '최종 상태가 경매 구성과 일치': () => ok }, { verify: 'final_state' });
    }
}
