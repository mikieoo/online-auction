import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

/**
 * D6 동시 입찰 부하 테스트 — 낙관적 락 검증.
 *
 * 사전 조건:
 *   1. 모든 서비스 실행 중 (discovery, gateway, auction, bid, payment)
 *   2. ACTIVE 상태의 경매가 존재해야 함
 *   3. 아래 환경변수로 설정:
 *      - GATEWAY_URL  (기본: http://localhost:8090)
 *      - AUCTION_ID   (테스트 대상 경매 ID)
 *      - TOKEN        (인증 토큰)
 *
 * 실행 예시:
 *   k6 run -e AUCTION_ID=1 -e TOKEN=xxx infra/k6/concurrent-bid-test.js
 */

const GATEWAY_URL = __ENV.GATEWAY_URL || 'http://localhost:8090';
const AUCTION_ID = __ENV.AUCTION_ID;
const TOKEN = __ENV.TOKEN;

if (!AUCTION_ID || !TOKEN) {
    throw new Error('AUCTION_ID와 TOKEN 환경변수가 필요합니다.');
}

// 커스텀 메트릭
const bidSuccess = new Counter('bid_success');
const bidConflict = new Counter('bid_conflict');         // 409 BID_CONFLICT (낙관적 락)
const bidTooLow = new Counter('bid_too_low');             // 400 BID_AMOUNT_TOO_LOW
const bidOtherError = new Counter('bid_other_error');
const conflictRate = new Rate('conflict_rate');
const bidDuration = new Trend('bid_duration', true);

export const options = {
    scenarios: {
        concurrent_bids: {
            executor: 'shared-iterations',
            vus: 20,              // 동시 사용자 수
            iterations: 200,      // 총 입찰 시도
            maxDuration: '60s',
        },
    },
    thresholds: {
        'bid_duration': ['p(95)<3000'],
    },
};

export default function () {
    // 각 VU가 고유 금액으로 입찰 (VU id + iteration 기반)
    // 시작가 10000 기준, VU마다 증가 단위(1001) * iteration 으로 충분히 높은 금액
    const baseAmount = 10000 + (__VU * 10000) + (__ITER * 1500);

    const payload = JSON.stringify({
        auctionId: parseInt(AUCTION_ID),
        amount: baseAmount,
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${TOKEN}`,
        },
    };

    const start = Date.now();
    const res = http.post(`${GATEWAY_URL}/api/v1/bids`, payload, params);
    bidDuration.add(Date.now() - start);

    if (res.status === 201) {
        bidSuccess.add(1);
        conflictRate.add(false);
    } else if (res.status === 409) {
        const body = JSON.parse(res.body || '{}');
        if (body.code === 'BID_CONFLICT') {
            bidConflict.add(1);
            conflictRate.add(true);
        } else {
            bidOtherError.add(1);
            conflictRate.add(false);
        }
    } else if (res.status === 400) {
        bidTooLow.add(1);
        conflictRate.add(false);
    } else {
        bidOtherError.add(1);
        conflictRate.add(false);
    }

    check(res, {
        'status is 201 or 409': (r) => r.status === 201 || r.status === 409 || r.status === 400,
    });

    sleep(0.05);
}

export function handleSummary(data) {
    const success = data.metrics.bid_success ? data.metrics.bid_success.values.count : 0;
    const conflict = data.metrics.bid_conflict ? data.metrics.bid_conflict.values.count : 0;
    const tooLow = data.metrics.bid_too_low ? data.metrics.bid_too_low.values.count : 0;
    const otherErr = data.metrics.bid_other_error ? data.metrics.bid_other_error.values.count : 0;
    const total = success + conflict + tooLow + otherErr;
    const p95 = data.metrics.bid_duration ? data.metrics.bid_duration.values['p(95)'] : 0;

    console.log('\n=== D6 동시 입찰 부하 테스트 결과 ===');
    console.log(`총 요청: ${total}`);
    console.log(`성공 (201): ${success}`);
    console.log(`낙관적 락 충돌 (409 BID_CONFLICT): ${conflict}`);
    console.log(`금액 부족 (400): ${tooLow}`);
    console.log(`기타 에러: ${otherErr}`);
    console.log(`충돌 비율: ${total > 0 ? ((conflict / total) * 100).toFixed(1) : 0}%`);
    console.log(`p95 응답 시간: ${p95.toFixed(0)}ms`);
    console.log('===================================\n');

    return {};
}
