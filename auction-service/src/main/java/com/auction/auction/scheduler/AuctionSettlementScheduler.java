package com.auction.auction.scheduler;

import com.auction.auction.client.BidGrpcClient;
import com.auction.auction.client.PaymentClient;
import com.auction.auction.client.PaymentRequest;
import com.auction.auction.client.PaymentResponse;
import com.auction.auction.client.WinnerResponse;
import com.auction.auction.client.WinningBidResponse;
import com.auction.auction.dto.SettlementTarget;
import com.auction.auction.service.AuctionSettlementService;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.grpc.StatusRuntimeException;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

/**
 * 마감(A단계)·정산(B단계) 주기 실행기. 이 빈은 트랜잭션을 열지 않는다.
 * DB 작업은 경매 1건 = 트랜잭션 1개로 {@link AuctionSettlementService}에 위임하고,
 * bid(gRPC)/payment(Feign) 호출은 그 트랜잭션들 사이(트랜잭션 밖)에서 수행한다.
 * 한 경매의 예외는 WARN 로그 후 다음 경매로 넘어가며, A단계는 B단계 결과와 무관하게 항상 먼저 완료된다.
 * 클라이언트 호출에서 나온 예외는 종류와 무관하게(StatusRuntimeException, FeignException,
 * CallNotPermittedException, 그 래퍼) "저장 없이 다음 주기 재시도"이고,
 * 서킷 브레이커가 OPEN이면 이번 틱의 남은 정산 대상을 건너뛴다.
 * ShedLock으로 다중 인스턴스에서 한 번에 하나만 실행된다.
 */
@Component
public class AuctionSettlementScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuctionSettlementScheduler.class);

    private static final String BID_SERVICE = "bid-service";
    private static final String PAYMENT_SERVICE = "payment-service";

    private final AuctionSettlementService settlementService;
    private final BidGrpcClient bidGrpcClient;
    private final PaymentClient paymentClient;
    private final int settlementBatchSize;

    public AuctionSettlementScheduler(AuctionSettlementService settlementService,
                                      BidGrpcClient bidGrpcClient,
                                      PaymentClient paymentClient,
                                      @Value("${scheduler.auction.settlement-batch-size:50}") int settlementBatchSize) {
        this.settlementService = settlementService;
        this.bidGrpcClient = bidGrpcClient;
        this.paymentClient = paymentClient;
        this.settlementBatchSize = settlementBatchSize;
    }

    @Scheduled(fixedDelayString = "${scheduler.auction.fixed-delay:5000}")
    @SchedulerLock(name = "auction-settlement", lockAtLeastFor = "PT1S")
    public void settle() {
        closeEndedAuctions();
        settleClosedAuctions();
    }

    /** A단계 — ACTIVE이고 end_time이 지난 경매를 각각 CLOSED로 저장한다. 외부 호출 없음. */
    private void closeEndedAuctions() {
        List<Long> auctionIds = settlementService.findEndedActiveAuctionIds();
        for (Long auctionId : auctionIds) {
            try {
                settlementService.closeAuction(auctionId);
                log.info("경매 마감 처리 완료. auctionId={}", auctionId);
            } catch (RuntimeException e) {
                log.warn("경매 마감 처리 실패, 다음 경매로 넘어갑니다. auctionId={}", auctionId, e);
            }
        }
    }

    /** B단계 — CLOSED이고 winner_id가 NULL인 경매를 틱당 상한만큼 정산한다. */
    private void settleClosedAuctions() {
        List<SettlementTarget> targets = settlementService.findSettlementTargets(settlementBatchSize);
        for (int i = 0; i < targets.size(); i++) {
            SettlementTarget target = targets.get(i);
            try {
                settleOne(target);
            } catch (ExternalCallFailedException e) {
                if (findCause(e, CallNotPermittedException.class) != null) {
                    log.warn("정산 중 외부 서비스 호출 실패(서킷 브레이커 OPEN), 이번 틱의 남은 정산을 중단하고 "
                                    + "다음 주기에 재시도합니다. client={}, auctionId={}, 미처리={}건",
                            e.getClientName(), target.getAuctionId(), targets.size() - i);
                    return;
                }
                log.warn("정산 중 외부 서비스 호출 실패, 다음 주기에 재시도합니다. client={}, auctionId={}, status={}",
                        e.getClientName(), target.getAuctionId(), extractStatusInfo(e), e.getCause());
            } catch (RuntimeException e) {
                log.warn("정산 처리 실패, 다음 경매로 넘어갑니다. auctionId={}", target.getAuctionId(), e);
            }
        }
    }

    private void settleOne(SettlementTarget target) {
        Long auctionId = target.getAuctionId();

        // 1. 낙찰 확정 (gRPC, 트랜잭션 밖). 실패 시 ExternalCallFailedException → 저장 없이 다음 주기.
        WinnerResponse winner = callExternal(BID_SERVICE, () -> bidGrpcClient.confirmWinner(auctionId));

        if (!winner.isHasBids()) {
            settlementService.markNoBids(auctionId);
            log.info("유찰 처리 완료. auctionId={}", auctionId);
            return;
        }

        WinningBidResponse winningBid = winner.getWinningBid();
        if (winningBid == null || winningBid.getBidderId() == null || winningBid.getAmount() == null) {
            throw new IllegalStateException(
                    "낙찰 확정 응답이 일관되지 않습니다(hasBids=true, winningBid 누락). auctionId=" + auctionId);
        }
        Long winnerId = winningBid.getBidderId();

        // 2. 결제 요청 (Feign, 트랜잭션 밖). 같은 키로 재호출하면 기존 Payment가 돌아온다.
        PaymentRequest paymentRequest = new PaymentRequest(
                auctionId, winnerId, winningBid.getAmount(),
                PaymentRequest.idempotencyKeyOf(auctionId, winnerId, target.getReassignmentCount()));
        PaymentResponse payment = callExternal(PAYMENT_SERVICE, () -> paymentClient.requestPayment(paymentRequest));

        if (payment.getStatus() == null) {
            throw new IllegalStateException("결제 응답에 status가 없습니다. auctionId=" + auctionId);
        }

        switch (payment.getStatus()) {
            case COMPLETED -> {
                settlementService.completeWithWinner(auctionId, winnerId, winningBid.getAmount());
                log.info("정산 완료(결제 성공). auctionId={}, winnerId={}, paymentId={}",
                        auctionId, winnerId, payment.getPaymentId());
            }
            case FAILED -> {
                settlementService.recordPaymentFailed(auctionId, winnerId, winningBid.getAmount());
                log.info("결제 실패 기록(CLOSED 유지, D10 대기). auctionId={}, winnerId={}, reason={}",
                        auctionId, winnerId, payment.getFailureReason());
            }
            case REQUESTED -> log.info("결제 진행 중, 다음 주기에 재확인합니다. auctionId={}, paymentId={}",
                    auctionId, payment.getPaymentId());
        }
    }

    /**
     * 클라이언트 호출에서 나온 예외를 저장 단계(AuctionSettlementService) 예외와 구분하기 위한 경계.
     * bid-service는 gRPC(StatusRuntimeException), payment-service는 Feign(FeignException)이므로
     * 타입을 나열하지 않고 "클라이언트 호출에서 나왔다"는 사실로 분류한다.
     */
    private static <T> T callExternal(String clientName, Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            throw new ExternalCallFailedException(clientName, e);
        }
    }

    /** 원인 사슬에서 gRPC/Feign 상태 정보를 추출한다. 로깅 전용. */
    private static String extractStatusInfo(ExternalCallFailedException e) {
        StatusRuntimeException grpc = findCause(e, StatusRuntimeException.class);
        if (grpc != null) {
            return grpc.getStatus().getCode().name();
        }
        FeignException feign = findCause(e, FeignException.class);
        if (feign != null) {
            return String.valueOf(feign.status());
        }
        return "N/A";
    }

    /** 원인 사슬에서 지정 타입을 찾는다. 순환 사슬에서 무한 루프를 돌지 않도록 깊이를 제한한다. */
    private static <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private static final class ExternalCallFailedException extends RuntimeException {

        private final String clientName;

        private ExternalCallFailedException(String clientName, RuntimeException cause) {
            super("외부 서비스 호출 실패. client=" + clientName, cause);
            this.clientName = clientName;
        }

        private String getClientName() {
            return clientName;
        }
    }
}
