package com.auction.bid.client;

import com.auction.bid.exception.AuctionNotFoundException;
import com.auction.bid.exception.UpstreamErrorException;
import com.auction.bid.exception.UpstreamUnavailableException;
import feign.FeignException;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

/**
 * fallback은 "빠른 거절"만 한다. 어떤 원인에서도 응답 객체를 돌려주지 않고 항상 예외를 던지는지,
 * 그리고 비즈니스 예외(404/502)가 503으로 뭉개지지 않는지를 검증한다.
 */
class AuctionClientFallbackFactoryTest {

    private static final Long AUCTION_ID = 10L;

    private final AuctionClientFallbackFactory factory = new AuctionClientFallbackFactory();

    private Throwable invokeFallback(Throwable cause) {
        return catchThrowable(() -> factory.create(cause).getAuction(AUCTION_ID));
    }

    private void assertUnavailable(Throwable thrown, Throwable expectedCause) {
        assertThat(thrown).isInstanceOf(UpstreamUnavailableException.class);
        UpstreamUnavailableException e = (UpstreamUnavailableException) thrown;
        assertThat(e.getCode()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(e.getStatus().value()).isEqualTo(503);
        assertThat(e.getCause()).isSameAs(expectedCause);
    }

    private CallNotPermittedException circuitOpen() {
        return CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("auction-service"));
    }

    @Test
    @DisplayName("원인이 AuctionNotFoundException(404)이면 같은 인스턴스를 그대로 다시 던진다")
    void auctionNotFoundRethrownUnchanged() {
        AuctionNotFoundException cause = new AuctionNotFoundException(AUCTION_ID);

        assertThat(invokeFallback(cause)).isSameAs(cause);
    }

    @Test
    @DisplayName("원인이 UpstreamErrorException(502)이면 같은 인스턴스를 그대로 다시 던진다")
    void upstreamErrorRethrownUnchanged() {
        UpstreamErrorException cause = new UpstreamErrorException("bad request");

        assertThat(invokeFallback(cause)).isSameAs(cause);
    }

    @Test
    @DisplayName("연결 실패·타임아웃(RetryableException)은 UPSTREAM_UNAVAILABLE(503)")
    void retryableExceptionMappedTo503() {
        RetryableException cause = mock(RetryableException.class);

        assertUnavailable(invokeFallback(cause), cause);
    }

    @Test
    @DisplayName("기타 FeignException도 UPSTREAM_UNAVAILABLE(503)")
    void genericFeignExceptionMappedTo503() {
        FeignException cause = mock(FeignException.class);

        assertUnavailable(invokeFallback(cause), cause);
    }

    @Test
    @DisplayName("상류 5xx(ErrorDecoder가 만든 UpstreamUnavailableException)는 503 그대로다")
    void upstream5xxStays503() {
        UpstreamUnavailableException cause =
                new UpstreamUnavailableException("auction-service 응답 오류입니다. status=500");

        Throwable thrown = invokeFallback(cause);

        assertThat(thrown).isInstanceOf(UpstreamUnavailableException.class);
        assertThat(((UpstreamUnavailableException) thrown).getCode()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(((UpstreamUnavailableException) thrown).getStatus().value()).isEqualTo(503);
    }

    @Test
    @DisplayName("서킷 OPEN(CallNotPermittedException)은 UPSTREAM_UNAVAILABLE(503)로 거절한다")
    void callNotPermittedMappedTo503() {
        CallNotPermittedException cause = circuitOpen();

        assertUnavailable(invokeFallback(cause), cause);
    }

    @Test
    @DisplayName("래퍼(RuntimeException) 안의 AuctionNotFoundException은 루트 비즈니스 원인으로 분류한다")
    void wrappedNotFoundClassifiedByRootCause() {
        AuctionNotFoundException root = new AuctionNotFoundException(AUCTION_ID);

        assertThat(invokeFallback(new RuntimeException(root))).isSameAs(root);
    }

    @Test
    @DisplayName("이중 래퍼(RuntimeException, ExecutionException) 안의 UpstreamErrorException도 그대로 다시 던진다")
    void doublyWrappedUpstreamErrorClassifiedByRootCause() {
        UpstreamErrorException root = new UpstreamErrorException("bad request");

        assertThat(invokeFallback(new RuntimeException(new ExecutionException(root)))).isSameAs(root);
    }

    @Test
    @DisplayName("래퍼 안의 서킷 OPEN과 알 수 없는 원인은 503")
    void wrappedNonBusinessCausesMappedTo503() {
        Throwable wrappedOpen = new RuntimeException(circuitOpen());
        Throwable wrappedTimeout = new ExecutionException(new TimeoutException("timeout"));

        assertUnavailable(invokeFallback(wrappedOpen), wrappedOpen);
        assertUnavailable(invokeFallback(wrappedTimeout), wrappedTimeout);
    }

    @Test
    @DisplayName("원인이 null이어도 응답을 만들어 내지 않고 503으로 거절한다 (degraded 수락 없음)")
    void nullCauseStillRejects() {
        assertThat(invokeFallback(null)).isInstanceOf(UpstreamUnavailableException.class);
    }
}
