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
 * 마감·정산 스케줄러의 DB 작업 단위. 메서드 하나 = 경매 한 건 = 트랜잭션 하나.
 *
 * <p>스케줄러 빈({@code AuctionSettlementScheduler})과 분리한 이유: {@code @Transactional}은 프록시로 동작하므로
 * 같은 빈 안에서 자기 메서드를 호출(self-invocation)하면 트랜잭션이 걸리지 않는다. 또한 Feign 호출은
 * 이 빈의 트랜잭션 밖(스케줄러 빈)에서 일어나야 커넥션을 원격 호출 시간만큼 잡고 있지 않는다.
 *
 * <p><b>D3 한정 임시 상태 인코딩</b> — 별도 결제 상태 컬럼 없이 status + winner_id 조합으로 정산 진행 단계를 표현한다:
 * <ul>
 *   <li>CLOSED + winner_id NULL : 정산 대기(B단계 대상)</li>
 *   <li>CLOSED + winner_id 있음 : 낙찰자 확정됐으나 결제 실패(D10 재지정 대기, B단계 대상에서 빠짐)</li>
 *   <li>COMPLETED               : 결제 성공</li>
 *   <li>FAILED  + winner_id NULL : 유찰(입찰 없음)</li>
 * </ul>
 * 재시도는 전부 멱등이다: 낙찰 확정은 같은 WINNER를, 결제는 같은 idempotencyKey의 기존 Payment를 돌려준다.
 *
 * <p>D9/D10에서 이 동기 Feign 호출은 Kafka 이벤트로 대체된다
 * (AuctionWon → PaymentCompleted / PaymentFailed → WinnerReassigned). 그때 위 임시 인코딩도 이벤트 기반 상태 전이로 바뀐다.
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

    /** B단계 결제 성공: 낙찰자 지정 + COMPLETED를 한 트랜잭션으로 저장. */
    @Transactional
    public void completeWithWinner(Long auctionId, Long winnerId, BigDecimal amount) {
        Auction auction = findAuction(auctionId);
        auction.assignWinner(winnerId, amount);
        auction.complete();
        auctionRepository.save(auction);
    }

    /** B단계 결제 실패: 낙찰자만 기록하고 CLOSED를 유지한다. winner_id가 채워져 다음 틱의 B단계 대상에서 빠진다. */
    @Transactional
    public void recordPaymentFailed(Long auctionId, Long winnerId, BigDecimal amount) {
        Auction auction = findAuction(auctionId);
        auction.assignWinner(winnerId, amount);
        auctionRepository.save(auction);
    }

    private Auction findAuction(Long auctionId) {
        return auctionRepository.findById(auctionId)
                .orElseThrow(() -> new NotFoundException("AUCTION_NOT_FOUND",
                        "경매를 찾을 수 없습니다. id=" + auctionId));
    }
}
