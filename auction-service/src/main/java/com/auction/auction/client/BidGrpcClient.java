package com.auction.auction.client;

import com.auction.common.grpc.BidWinnerServiceGrpc;
import com.auction.common.grpc.ConfirmWinnerRequest;
import com.auction.common.grpc.ConfirmWinnerResponse;
import com.auction.common.grpc.WinningBid;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.grpc.Channel;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * bid-service 낙찰 확정 gRPC 클라이언트. 기존 Feign {@link BidClient}를 대체한다.
 * <p>
 * Resilience4j CircuitBreaker를 직접 감싼다. Feign + Spring Cloud CB 통합과 달리
 * gRPC에는 자동 통합이 없으므로 {@link CircuitBreaker#decorateSupplier}로 수동 연동한다.
 * CB 인스턴스 이름은 기존과 동일한 "bid-service"를 재사용한다(application.yml의 설정이 그대로 적용).
 * <p>
 * 예외 흐름:
 * <ul>
 *   <li>CB OPEN → {@link io.github.resilience4j.circuitbreaker.CallNotPermittedException}</li>
 *   <li>gRPC 오류(CB CLOSED) → {@link StatusRuntimeException}</li>
 * </ul>
 * 두 가지 모두 RuntimeException이므로 스케줄러의 {@code callExternal} 경계에서 잡힌다.
 */
@Component
public class BidGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(BidGrpcClient.class);

    private final BidWinnerServiceGrpc.BidWinnerServiceBlockingStub stub;
    private final CircuitBreaker circuitBreaker;
    private final long deadlineMs;

    public BidGrpcClient(@GrpcClient("bid-service") Channel channel,
                          CircuitBreakerRegistry circuitBreakerRegistry,
                          @Value("${clients.bid-service.read-timeout-ms:5000}") long deadlineMs) {
        this.stub = BidWinnerServiceGrpc.newBlockingStub(channel);
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("bid-service");
        this.deadlineMs = deadlineMs;
    }

    /**
     * 낙찰 확정. 멱등하다(같은 경매에 재호출하면 같은 WINNER를 돌려준다).
     * 입찰이 없는 경우 hasBids=false로 돌아오며 예외가 아니다.
     */
    public WinnerResponse confirmWinner(Long auctionId) {
        Supplier<WinnerResponse> decorated = CircuitBreaker.decorateSupplier(
                circuitBreaker,
                () -> {
                    ConfirmWinnerRequest request = ConfirmWinnerRequest.newBuilder()
                            .setAuctionId(auctionId)
                            .build();
                    ConfirmWinnerResponse response = stub
                            .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                            .confirmWinner(request);
                    return toWinnerResponse(response);
                });
        return decorated.get();
    }

    private static WinnerResponse toWinnerResponse(ConfirmWinnerResponse response) {
        if (!response.getHasBids() || !response.hasWinningBid()) {
            return new WinnerResponse(response.getAuctionId(), false, null);
        }

        WinningBid bid = response.getWinningBid();
        WinningBidResponse winningBid = new WinningBidResponse(
                bid.getBidId(),
                bid.getAuctionId(),
                bid.getBidderId(),
                new BigDecimal(bid.getAmount()),
                bid.getStatus(),
                LocalDateTime.parse(bid.getCreatedAt()));
        return new WinnerResponse(response.getAuctionId(), true, winningBid);
    }
}
