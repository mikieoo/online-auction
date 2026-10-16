import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import {
    GATEWAY_URL, OUTCOME_THRESHOLDS, authHeaders, classifyBid, createActiveAuction, issueToken, issueTokens,
} from './lib/api.js';

/**
 * 시나리오 1 — 인기 경매 하나에 입찰이 몰릴 때(마감 직전 상황).
 *
 * 보는 것:
 *   - 경매 단위 분산 락이 요청을 한 줄로 세울 때 지연이 얼마나 늘어나는가 (p95, p99)
 *   - 락 대기 3초를 넘겨 BID_CONFLICT가 나기 시작하는 도착률
 *   - 끝난 뒤 불변 조건: ACTIVE 입찰은 정확히 1개, 그 금액이 최고가
 *     (저장된 입찰 수는 teardown 로그에 남긴다 — 요약의 accepted 수 + 워밍업 수락 수와 같아야 한다)
 *
 * 환경 변수: RATE(초당 입찰, 기본 30), DURATION(기본 60s), BIDDERS(입찰자 수, 기본 50)
 */

const RATE = parseInt(__ENV.RATE || '30');
const DURATION = __ENV.DURATION || '60s';
const BIDDERS = parseInt(__ENV.BIDDERS || '50');
const STARTING_PRICE = 10000;
const INCREMENT = 1000; // bid.increment
const WARMUP = '10s';

const outcomes = new Counter('bid_outcomes');
const bidLatency = new Trend('bid_latency', true);

export const options = {
    setupTimeout: '120s',
    scenarios: {
        // JIT·커넥션 풀이 데워지기 전의 느린 응답을 측정에서 뺀다.
        warmup: {
            executor: 'constant-arrival-rate',
            rate: 5,
            timeUnit: '1s',
            duration: WARMUP,
            preAllocatedVUs: 10,
            tags: { phase: 'warmup' },
            env: { AMOUNT_BASE: '0' },
        },
        hot_auction: {
            executor: 'constant-arrival-rate',
            startTime: WARMUP,
            rate: RATE,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: Math.max(RATE * 4, 20),
            maxVUs: RATE * 10,
            tags: { phase: 'measure' },
            // 워밍업에서 올라간 최고가보다 높은 금액대에서 시작한다.
            env: { AMOUNT_BASE: '10000000' },
        },
    },
    thresholds: {
        ...OUTCOME_THRESHOLDS,
        'bid_latency{phase:measure}': ['p(95)<1000', 'p(99)<3000'],
        'checks{verify:invariant}': ['rate==1'],
    },
};

export function setup() {
    const sellerToken = issueToken(1);
    // 측정 시간 + 여유. 마감되면 입찰이 409로 바뀌어 측정이 오염된다.
    const auction = createActiveAuction(sellerToken, {
        startingPrice: STARTING_PRICE,
        endInSeconds: 900,
        name: 'k6 hot auction',
    });
    const bidders = issueTokens(1001, BIDDERS);
    return { auctionId: auction.auctionId, bidders };
}

export default function (data) {
    const bidder = data.bidders[Math.floor(Math.random() * data.bidders.length)];
    // 반복 번호로 금액을 올린다. 동시에 도착한 요청은 락 안에서 순서가 섞이므로
    // 늦게 처리된 낮은 금액은 BID_AMOUNT_TOO_LOW가 된다 — 실제 경합과 같은 모양이다.
    const amount = STARTING_PRICE + parseInt(__ENV.AMOUNT_BASE)
        + (exec.scenario.iterationInTest + 1) * INCREMENT * 2;

    const res = http.post(`${GATEWAY_URL}/api/v1/bids`,
        JSON.stringify({ auctionId: data.auctionId, amount }),
        { headers: authHeaders(bidder.token), tags: { name: 'POST /api/v1/bids' } });

    const outcome = classifyBid(res);
    outcomes.add(1, { outcome });
    bidLatency.add(res.timings.duration, { outcome });
}

export function teardown(data) {
    const res = http.get(`${GATEWAY_URL}/api/v1/bids?auctionId=${data.auctionId}`, { tags: { phase: 'verify' } });
    const bids = res.json();
    const active = bids.filter((b) => b.status === 'ACTIVE');
    const maxAmount = bids.reduce((m, b) => Math.max(m, Number(b.amount)), 0);

    console.log(`[verify] auctionId=${data.auctionId} 저장된 입찰=${bids.length} ACTIVE=${active.length} 최고가=${maxAmount}`);

    // 체크 이름 태그(check)는 k6가 덮어쓰므로 별도 키(verify)로 묶는다.
    check(null, {
        'ACTIVE 입찰은 정확히 1개': () => active.length === 1,
        'ACTIVE 입찰이 최고가': () => active.length === 1 && Number(active[0].amount) === maxAmount,
    }, { verify: 'invariant' });
}
