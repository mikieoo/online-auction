package com.auction.auction.scheduler;

import com.auction.auction.client.BidGrpcClient;
import com.auction.auction.client.WinnerResponse;
import com.auction.auction.client.WinningBidResponse;
import com.auction.auction.dto.SettlementTarget;
import com.auction.auction.event.AuctionEventProducer;
import com.auction.auction.service.AuctionSettlementService;
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
 * bid-service gRPC 호출은 트랜잭션 밖에서 수행한다.
 *
 * <p><b>D9 Saga 전환:</b> 결제 요청이 동기 Feign에서 Kafka 이벤트(AuctionWonEvent)로 바뀌었다.
 * B단계에서 낙찰자를 확정하면 winner_id를 DB에 기록하고 AuctionWonEvent를 발행한다.
 * 결제 결과는 PaymentCompletedEvent/PaymentFailedEvent로 비동기 수신한다({@link
 * com.auction.auction.event.PaymentEventConsumer}).
 *
 * <p>ShedLock으로 다중 인스턴스에서 한 번에 하나만 실행된다.
 */
@Component
public class AuctionSettlementScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuctionSettlementScheduler.class);

    private static final String BID_SERVICE = "bid-service";

    private final AuctionSettlementService settlementService;
    private final BidGrpcClient bidGrpcClient;
    private final AuctionEventProducer eventProducer;
    private final int settlementBatchSize;

    public AuctionSettlementScheduler(AuctionSettlementService settlementService,
                                      BidGrpcClient bidGrpcClient,
                                      AuctionEventProducer eventProducer,
                                      @Value("${scheduler.auction.settlement-batch-size:50}") int settlementBatchSize) {
        this.settlementService = settlementService;
        this.bidGrpcClient = bidGrpcClient;
        this.eventProducer = eventProducer;
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
                eventProducer.publishClosed(auctionId);
                log.info("경매 마감 처리 완료. auctionId={}", auctionId);
            } catch (RuntimeException e) {
                log.warn("경매 마감 처리 실패, 다음 경매로 넘어갑니다. auctionId={}", auctionId, e);
            }
        }
    }

    /** B단계 — CLOSED이고 winner_id가 NULL인 경매의 낙찰자를 확정하고 결제 이벤트를 발행한다. */
    private void settleClosedAuctions() {
        List<SettlementTarget> targets = settlementService.findSettlementTargets(settlementBatchSize);
        for (int i = 0; i < targets.size(); i++) {
            SettlementTarget target = targets.get(i);
            try {
                settleOne(target);
            } catch (ExternalCallFailedException e) {
                if (findCause(e, CallNotPermittedException.class) != null) {
                    log.warn("정산 중 bid-service 호출 실패(서킷 브레이커 OPEN), 이번 틱의 남은 정산을 중단하고 "
                                    + "다음 주기에 재시도합니다. auctionId={}, 미처리={}건",
                            target.getAuctionId(), targets.size() - i);
                    return;
                }
                log.warn("정산 중 bid-service 호출 실패, 다음 주기에 재시도합니다. auctionId={}, status={}",
                        target.getAuctionId(), extractStatusInfo(e), e.getCause());
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

        // 2. 낙찰자 DB 기록 + AuctionWonEvent 발행. 결제는 payment-service가 비동기로 처리한다.
        String idempotencyKey = auctionId + "-" + winnerId + "-" + target.getReassignmentCount();
        settlementService.assignWinnerForPayment(auctionId, winnerId, winningBid.getAmount());
        eventProducer.publishWon(auctionId, winnerId, winningBid.getAmount(), idempotencyKey);
        log.info("낙찰 확정 및 결제 요청 이벤트 발행. auctionId={}, winnerId={}", auctionId, winnerId);
    }

    /**
     * 클라이언트 호출에서 나온 예외를 저장 단계(AuctionSettlementService) 예외와 구분하기 위한 경계.
     */
    private static <T> T callExternal(String clientName, Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            throw new ExternalCallFailedException(clientName, e);
        }
    }

    /** 원인 사슬에서 gRPC 상태 정보를 추출한다. 로깅 전용. */
    private static String extractStatusInfo(ExternalCallFailedException e) {
        StatusRuntimeException grpc = findCause(e, StatusRuntimeException.class);
        if (grpc != null) {
            return grpc.getStatus().getCode().name();
        }
        return "N/A";
    }

    /** 원인 사슬에서 지정 타입을 찾는다. */
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
