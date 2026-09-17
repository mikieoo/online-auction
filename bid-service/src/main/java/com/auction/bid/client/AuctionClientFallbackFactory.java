package com.auction.bid.client;

import com.auction.bid.exception.AuctionNotFoundException;
import com.auction.bid.exception.BidException;
import com.auction.bid.exception.UpstreamErrorException;
import com.auction.bid.exception.UpstreamUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * auction-service 호출 실패를 bid-service 예외로 바꾸는 유일한 지점(빠른 거절 전용 fallback).
 *
 * 왜 "대체 응답"이 아니라 예외를 던지는가: 경매 상태(판매자·시작가·ACTIVE 여부·마감 시각)를 모르는 채로 입찰을 받으면
 * 본인 입찰·마감 후 입찰이 통과한다. 그래서 degraded 수락은 없고, fallback은 빨리 거절하는 일만 한다.
 *
 * 왜 비즈니스 예외를 다시 던지는가: Resilience4j는 ignore-exceptions로 실패 집계에서 뺀 예외에 대해서도 fallback을
 * 호출한다. 여기서 그대로 다시 던지지 않으면 404(AUCTION_NOT_FOUND)·502(UPSTREAM_ERROR)가 전부 503으로 뭉개진다.
 */
@Component
public class AuctionClientFallbackFactory implements FallbackFactory<AuctionClient> {

    /** 원인 체인에 순환 참조가 있어도 끝나도록 두는 상한. 실제 깊이는 2~3단이다. */
    private static final int MAX_CAUSE_DEPTH = 10;

    @Override
    public AuctionClient create(Throwable cause) {
        // 예외는 create()가 아니라 메서드 호출 시점에 던진다 — auctionId를 메시지에 넣을 수 있고,
        // Feign이 fallback 메서드 호출에서 나온 RuntimeException을 그대로 호출부로 풀어 준다.
        return auctionId -> {
            throw toBidException(cause, auctionId);
        };
    }

    /**
     * 분류 규칙. 래퍼(RuntimeException, ExecutionException 등) 안쪽까지 원인 체인을 따라가 본다.
     * - AuctionNotFoundException(404) / UpstreamErrorException(502): ErrorDecoder가 만든 그 인스턴스를 그대로 반환
     * - UpstreamUnavailableException(상류 5xx): 그대로 반환 (이미 503)
     * - 그 외(연결 실패·타임아웃 FeignException, 서킷 OPEN, 알 수 없는 오류): 503 UPSTREAM_UNAVAILABLE
     */
    BidException toBidException(Throwable cause, Long auctionId) {
        Throwable current = cause;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof AuctionNotFoundException
                    || current instanceof UpstreamErrorException
                    || current instanceof UpstreamUnavailableException) {
                return (BidException) current;
            }
            if (current instanceof CallNotPermittedException) {
                return new UpstreamUnavailableException(
                        "auction-service 서킷 브레이커가 열려 있어 호출을 차단했습니다. auctionId=" + auctionId, cause);
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return new UpstreamUnavailableException(
                "auction-service에 연결할 수 없습니다. auctionId=" + auctionId, cause);
    }
}
