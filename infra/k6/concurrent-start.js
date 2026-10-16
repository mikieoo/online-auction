import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { GATEWAY_URL, authHeaders, issueToken, serverLocalDateTime } from './lib/api.js';

/**
 * 같은 상품의 WAITING 경매 두 개를 동시에 시작한다 — "상품당 ACTIVE 경매 1개" 경합 재현.
 *
 * 사전 검사(existsByProductIdAndStatus)는 두 요청이 함께 통과할 수 있다. 그때 uk_auction_active_product가
 * 한쪽을 막아야 한다. 매 시도마다 정확히 200 하나 + 409 ACTIVE_AUCTION_EXISTS 하나여야 한다.
 * DB 제약이 실제로 막은 횟수는 auction-service 로그 "동시 시작을 DB 제약이 막음"으로 센다.
 *
 * 환경 변수: TRIALS(기본 50)
 */

const TRIALS = parseInt(__ENV.TRIALS || '50');
const TAGS = { phase: 'setup' };

const bothStarted = new Counter('both_started');       // 불변 조건 위반 (D20까지 가능했던 결과)
const oneStarted = new Counter('exactly_one_started');

export const options = {
    scenarios: {
        race: { executor: 'per-vu-iterations', vus: 1, iterations: TRIALS, maxDuration: '5m' },
    },
    thresholds: {
        both_started: ['count==0'],
        'checks{verify:race}': ['rate==1'],
    },
};

export function setup() {
    return { token: issueToken(1) };
}

function createWaitingAuction(headers, productId) {
    const res = http.post(`${GATEWAY_URL}/api/v1/auctions`,
        JSON.stringify({ productId, endTime: serverLocalDateTime(600) }), { headers, tags: TAGS });
    if (res.status !== 201) throw new Error(`경매 생성 실패 status=${res.status} body=${res.body}`);
    return res.json('auctionId');
}

export default function (data) {
    const headers = authHeaders(data.token);
    const product = http.post(`${GATEWAY_URL}/api/v1/products`,
        JSON.stringify({ name: 'k6 race', description: 'concurrent start', startingPrice: 10000 }),
        { headers, tags: TAGS });
    const productId = product.json('productId');
    const a = createWaitingAuction(headers, productId);
    const b = createWaitingAuction(headers, productId);

    // http.batch는 요청을 병렬로 보낸다.
    const [ra, rb] = http.batch([
        { method: 'PATCH', url: `${GATEWAY_URL}/api/v1/auctions/${a}/start`, params: { headers } },
        { method: 'PATCH', url: `${GATEWAY_URL}/api/v1/auctions/${b}/start`, params: { headers } },
    ]);

    const statuses = [ra.status, rb.status].sort();
    if (statuses[0] === 200 && statuses[1] === 200) bothStarted.add(1);
    if (statuses[0] === 200 && statuses[1] === 409) oneStarted.add(1);

    const loser = ra.status === 409 ? ra : rb;
    check(null, {
        '정확히 하나만 시작': () => statuses[0] === 200 && statuses[1] === 409,
        '거절 코드는 ACTIVE_AUCTION_EXISTS': () => loser.status !== 409 || loser.json('code') === 'ACTIVE_AUCTION_EXISTS',
    }, { verify: 'race' });
}
