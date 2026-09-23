package com.auction.auction.service;

import com.auction.auction.domain.Auction;
import com.auction.auction.domain.AuctionStatus;
import com.auction.auction.dto.SettlementTarget;
import com.auction.auction.exception.NotFoundException;
import com.auction.auction.repository.AuctionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 마감·정산의 DB 작업 단위. 메서드 하나 = 경매 한 건 = 트랜잭션 하나.
 *
 * <p>스케줄러 빈({@code AuctionSettlementScheduler})·이벤트 컨슈머와 분리한 이유:
 * {@code @Transactional}은 프록시로 동작하므로 같은 빈 안에서 자기 메서드를 호출(self-invocation)하면
 * 트랜잭션이 걸리지 않는다.
 *
 * <p><b>상태 인코딩</b> — status + winner_id 조합으로 정산 진행 단계를 표현한다:
 * <ul>
 *   <li>CLOSED + winner_id NULL : 정산 대기(B단계 대상)</li>
 *   <li>CLOSED + winner_id 있음 : 낙찰자 확정, 결제 대기 중(AuctionWonEvent 발행됨) 또는 결제 실패(D10 재지정 대기)</li>
 *   <li>COMPLETED               : 결제 성공(PaymentCompletedEvent 수신)</li>
 *   <li>FAILED  + winner_id NULL : 유찰(입찰 없음)</li>
 * </ul>
 */
@Service
public class AuctionSettlementService {

    private final AuctionRepository auctionRepository;

    public AuctionSettlementService(AuctionRepository auctionRepository) {
        this.auctionRepository = auctionRepository;
    }

    /** A단계 대상: ACTIVE이면서 end_time이 현재 시각 이전인 경매 id. */
    @Transactional(readOnly = true)
    public List<Long> findEndedActiveAuctionIds() {
        return auctionRepository.findByStatusAndEndTimeBefore(AuctionStatus.ACTIVE, LocalDateTime.now())
                .stream()
                .map(Auction::getAuctionId)
                .toList();
    }

    /** B단계 대상: CLOSED이면서 winner_id가 NULL인 경매를 auction_id 오름차순으로 최대 limit건. */
    @Transactional(readOnly = true)
    public List<SettlementTarget> findSettlementTargets(int limit) {
        return auctionRepository.findByStatusAndWinnerIdIsNull(
                        AuctionStatus.CLOSED, PageRequest.of(0, limit, Sort.by("auctionId")))
                .stream()
                .map(auction -> new SettlementTarget(auction.getAuctionId(), auction.getReassignmentCount()))
                .toList();
    }

    /** A단계: ACTIVE → CLOSED. 외부 호출 없음. */
    @Transactional
    public void closeAuction(Long auctionId) {
        Auction auction = findAuction(auctionId);
        auction.close();
        auctionRepository.save(auction);
    }

    /** B단계 유찰: hasBids=false → CLOSED → FAILED, winner_id는 NULL 유지. */
    @Transactional
    public void markNoBids(Long auctionId) {
        Auction auction = findAuction(auctionId);
        auction.fail();
        auctionRepository.save(auction);
    }

    /**
     * B단계 낙찰자 확정: winner_id를 기록하고 CLOSED를 유지한다.
     * winner_id가 채워져 다음 틱의 B단계 대상에서 빠진다.
     * 호출 후 AuctionWonEvent를 발행하면 payment-service가 비동기로 결제를 처리한다.
     */
    @Transactional
    public void assignWinnerForPayment(Long auctionId, Long winnerId, BigDecimal amount) {
        Auction auction = findAuction(auctionId);
        auction.assignWinner(winnerId, amount);
        auctionRepository.save(auction);
    }

    /** PaymentCompletedEvent 수신 시: CLOSED → COMPLETED. 이미 COMPLETED이면 무시(멱등). */
    @Transactional
    public void completeAuction(Long auctionId) {
        Auction auction = findAuction(auctionId);
        if (auction.getStatus() == AuctionStatus.COMPLETED) {
            return;
        }
        auction.complete();
        auctionRepository.save(auction);
    }

    private Auction findAuction(Long auctionId) {
        return auctionRepository.findById(auctionId)
                .orElseThrow(() -> new NotFoundException("AUCTION_NOT_FOUND",
                        "경매를 찾을 수 없습니다. id=" + auctionId));
    }
}
