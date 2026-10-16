import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import {
    GATEWAY_URL, OUTCOME_THRESHOLDS, authHeaders, classifyBid, createActiveAuction, issueToken, issueTokens,
} from './lib/api.js';

/**
 * 시나리오 2 — 여러 경매에 입찰이 흩어질 때의 처리량 한계.
 *
 * 경매 단위 락끼리는 서로 막지 않으므로, 여기서 먼저 한계에 닿는 것은 락이 아니라
 * 입찰 1건이 거치는 경로 전체(Gateway → bid → Feign으로 auction 조회 → MySQL 쓰기)다.
 * 도착률을 단계적으로 올리며 p95가 꺾이고 실패가 생기기 시작하는 지점을 찾는다.
 *
 * 환경 변수: AUCTIONS(기본 50), BIDDERS(기본 100), PEAK(최고 초당 입찰, 기본 200)
 */

const AUCTIONS = parseInt(__ENV.AUCTIONS || '50');
const BIDDERS = parseInt(__ENV.BIDDERS || '100');
const PEAK = parseInt(__ENV.PEAK || '200');
const STARTING_PRICE = 10000;
const INCREMENT = 1000;

const outcomes = new Counter('bid_outcomes');
const bidLatency = new Trend('bid_latency', true);

export const options = {
    setupTimeout: '180s',
    scenarios: {
        ramp: {
            executor: 'ramping-arrival-rate',
            startRate: 10,
            timeUnit: '1s',
            preAllocatedVUs: 50,
            maxVUs: PEAK * 5,
            stages: [
                { target: Math.round(PEAK * 0.25), duration: '30s' },
                { target: Math.round(PEAK * 0.25), duration: '30s' },
                { target: Math.round(PEAK * 0.5), duration: '30s' },
                { target: Math.round(PEAK * 0.5), duration: '30s' },
                { target: PEAK, duration: '30s' },
                { target: PEAK, duration: '30s' },
            ],
            tags: { phase: 'measure' },
        },
    },
    thresholds: {
        ...OUTCOME_THRESHOLDS,
        'bid_latency': ['p(95)<500'],
    },
};

export function setup() {
    const seller = issueToken(1);
    const auctionIds = [];
    for (let i = 0; i < AUCTIONS; i++) {
        auctionIds.push(createActiveAuction(seller, {
            startingPrice: STARTING_PRICE, endInSeconds: 900, name: `k6 throughput ${i}`,
        }).auctionId);
    }
    return { auctionIds, bidders: issueTokens(3001, BIDDERS) };
}

export default function (data) {
    const auctionId = data.auctionIds[Math.floor(Math.random() * data.auctionIds.length)];
    const bidder = data.bidders[Math.floor(Math.random() * data.bidders.length)];
    // 전역적으로 증가하는 금액 → 같은 경매 안에서도 대부분 직전 최고가보다 높다(정상 수락 경로를 잰다).
    const amount = STARTING_PRICE + (exec.scenario.iterationInTest + 1) * INCREMENT * 2;

    const res = http.post(`${GATEWAY_URL}/api/v1/bids`,
        JSON.stringify({ auctionId, amount }),
        { headers: authHeaders(bidder.token), tags: { name: 'POST /api/v1/bids' } });

    const outcome = classifyBid(res);
    outcomes.add(1, { outcome });
    bidLatency.add(res.timings.duration, { outcome });
}
