import http from 'k6/http';
import { fail } from 'k6';

/**
 * 부하 테스트 시나리오가 공유하는 API 헬퍼.
 * setup()에서 토큰 발급·상품/경매 생성처럼 측정 대상이 아닌 준비 작업에 쓴다.
 */

export const GATEWAY_URL = __ENV.GATEWAY_URL || 'http://localhost:8080';

// 준비 요청은 측정에서 빼기 위해 태그를 단다(thresholds는 phase:measure만 본다).
const SETUP_TAGS = { phase: 'setup' };

export function authHeaders(token) {
    return {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
    };
}

/** gateway의 local 프로필 전용 발급기(POST /auth/token)로 토큰을 받는다. */
export function issueToken(userId) {
    const res = http.post(`${GATEWAY_URL}/auth/token`, JSON.stringify({ userId }), {
        headers: { 'Content-Type': 'application/json' },
        tags: SETUP_TAGS,
    });
    if (res.status !== 200) {
        fail(`토큰 발급 실패 userId=${userId} status=${res.status} body=${res.body}`);
    }
    return res.json('accessToken');
}

/** userId → token 맵. 입찰자를 서로 다른 사용자로 만들어야 판매자 본인 입찰·동일인 경합이 섞이지 않는다. */
export function issueTokens(fromUserId, count) {
    const tokens = [];
    for (let i = 0; i < count; i++) {
        const userId = fromUserId + i;
        tokens.push({ userId, token: issueToken(userId) });
    }
    return tokens;
}

/**
 * 서비스 JVM의 LocalDateTime.now() 기준 시각 문자열.
 * 컨테이너 JVM은 UTC이므로 UTC 벽시계 시각을 오프셋 없이 보낸다.
 */
export function serverLocalDateTime(offsetSeconds) {
    return new Date(Date.now() + offsetSeconds * 1000).toISOString().replace('Z', '').substring(0, 19);
}

/** 상품 등록 → 경매 생성 → 시작까지 마친 ACTIVE 경매를 만든다. */
export function createActiveAuction(sellerToken, { startingPrice, endInSeconds, name }) {
    const headers = authHeaders(sellerToken);

    const product = http.post(`${GATEWAY_URL}/api/v1/products`,
        JSON.stringify({ name: name || 'k6 상품', description: 'load test', startingPrice }),
        { headers, tags: SETUP_TAGS });
    if (product.status !== 201) fail(`상품 등록 실패 status=${product.status} body=${product.body}`);

    const auction = http.post(`${GATEWAY_URL}/api/v1/auctions`,
        JSON.stringify({ productId: product.json('productId'), endTime: serverLocalDateTime(endInSeconds) }),
        { headers, tags: SETUP_TAGS });
    if (auction.status !== 201) fail(`경매 생성 실패 status=${auction.status} body=${auction.body}`);

    const auctionId = auction.json('auctionId');
    const started = http.patch(`${GATEWAY_URL}/api/v1/auctions/${auctionId}/start`, null,
        { headers, tags: SETUP_TAGS });
    if (started.status !== 200) fail(`경매 시작 실패 status=${started.status} body=${started.body}`);

    return { auctionId, startingPrice, endTime: auction.json('endTime') };
}

/**
 * 결과별 건수를 요약에 보이게 하는 thresholds. k6는 thresholds에 적힌 하위 메트릭만 요약에 따로 출력한다.
 * 업무 거절(too_low, not_active)은 정상 결과라 조건 없이 세기만 하고, 시스템 실패만 0을 요구한다.
 */
export const OUTCOME_THRESHOLDS = {
    'bid_outcomes{outcome:accepted}': ['count>=0'],
    'bid_outcomes{outcome:too_low}': ['count>=0'],
    'bid_outcomes{outcome:conflict}': ['count>=0'],
    'bid_outcomes{outcome:not_active}': ['count>=0'],
    'bid_outcomes{outcome:unavailable}': ['count==0'],
    'bid_outcomes{outcome:error}': ['count==0'],
};

/** 응답을 업무 결과 단위로 분류한다. 분류 이름이 곧 메트릭 태그다. */
export function classifyBid(res) {
    if (res.status === 201) return 'accepted';
    let code = '';
    try {
        code = res.json('code') || '';
    } catch (e) {
        code = '';
    }
    if (res.status === 400 && code === 'BID_AMOUNT_TOO_LOW') return 'too_low';
    // 분산 락 대기(3초) 초과와 @Version 충돌이 같은 코드로 나온다.
    if (res.status === 409 && code === 'BID_CONFLICT') return 'conflict';
    if (res.status === 409) return 'not_active';
    if (res.status === 503) return 'unavailable';
    return 'error';
}
