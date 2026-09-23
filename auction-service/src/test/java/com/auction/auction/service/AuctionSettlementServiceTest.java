package com.auction.auction.service;

import com.auction.auction.domain.Auction;
import com.auction.auction.domain.AuctionStatus;
import com.auction.auction.dto.SettlementTarget;
import com.auction.auction.exception.NotFoundException;
import com.auction.auction.repository.AuctionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuctionSettlementServiceTest {

    private static final Long AUCTION_ID = 100L;
    private static final Long PRODUCT_ID = 1L;
    private static final Long WINNER_ID = 77L;
    private static final BigDecimal PRICE = new BigDecimal("15000.00");

    @Mock
    private AuctionRepository auctionRepository;

    private AuctionSettlementService service;

    @BeforeEach
    void setUp() {
        service = new AuctionSettlementService(auctionRepository);
    }

    private static Auction waitingAuction() {
        Auction auction = new Auction(PRODUCT_ID, LocalDateTime.now().plusMinutes(1));
        ReflectionTestUtils.setField(auction, "auctionId", AUCTION_ID);
        return auction;
    }

    private static Auction activeAuction() {
        Auction auction = waitingAuction();
        auction.start();
        return auction;
    }

    private static Auction closedAuction() {
        Auction auction = activeAuction();
        auction.close();
        return auction;
    }

    // ---------- 조회 ----------

    @Test
    @DisplayName("findEndedActiveAuctionIds: ACTIVE이면서 end_time이 지난 경매 id만 반환한다")
    void findEndedActiveAuctionIds_queriesActiveBeforeNow() {
        Auction ended = activeAuction();
        when(auctionRepository.findByStatusAndEndTimeBefore(eq(AuctionStatus.ACTIVE), any(LocalDateTime.class)))
                .thenReturn(List.of(ended));

        List<Long> ids = service.findEndedActiveAuctionIds();

        assertThat(ids).containsExactly(AUCTION_ID);
        ArgumentCaptor<LocalDateTime> timeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(auctionRepository).findByStatusAndEndTimeBefore(eq(AuctionStatus.ACTIVE), timeCaptor.capture());
        assertThat(timeCaptor.getValue()).isBeforeOrEqualTo(LocalDateTime.now().plusSeconds(1));
    }

    @Test
    @DisplayName("findSettlementTargets: CLOSED + winner_id NULL을 상한 크기로 조회하고 reassignmentCount를 함께 돌려준다")
    void findSettlementTargets_queriesClosedWithoutWinnerLimited() {
        Auction closed = closedAuction();
        closed.incrementReassignmentCount();
        when(auctionRepository.findByStatusAndWinnerIdIsNull(eq(AuctionStatus.CLOSED), any(Pageable.class)))
                .thenReturn(List.of(closed));

        List<SettlementTarget> targets = service.findSettlementTargets(50);

        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).getAuctionId()).isEqualTo(AUCTION_ID);
        assertThat(targets.get(0).getReassignmentCount()).isEqualTo(1);
        ArgumentCaptor<Pageable> pageCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(auctionRepository).findByStatusAndWinnerIdIsNull(eq(AuctionStatus.CLOSED), pageCaptor.capture());
        assertThat(pageCaptor.getValue().getPageSize()).isEqualTo(50);
        assertThat(pageCaptor.getValue().getPageNumber()).isZero();
    }

    // ---------- closeAuction ----------

    @Test
    @DisplayName("closeAuction: ACTIVE 경매를 CLOSED로 저장한다")
    void closeAuction_activeBecomesClosed() {
        Auction auction = activeAuction();
        when(auctionRepository.findById(AUCTION_ID)).thenReturn(Optional.of(auction));

        service.closeAuction(AUCTION_ID);

        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.CLOSED);
        verify(auctionRepository).save(auction);
    }

    @Test
    @DisplayName("closeAuction: ACTIVE가 아닌 경매(WAITING)는 예외, 저장 없음")
    void closeAuction_nonActiveThrows() {
        Auction auction = waitingAuction();
        when(auctionRepository.findById(AUCTION_ID)).thenReturn(Optional.of(auction));

        assertThatThrownBy(() -> service.closeAuction(AUCTION_ID))
                .isInstanceOf(IllegalStateException.class);
        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.WAITING);
        verify(auctionRepository, never()).save(any());
    }

    @Test
    @DisplayName("closeAuction: 존재하지 않는 경매는 NotFoundException")
    void closeAuction_missingThrowsNotFound() {
        when(auctionRepository.findById(AUCTION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.closeAuction(AUCTION_ID))
                .isInstanceOf(NotFoundException.class);
    }

    // ---------- markNoBids ----------

    @Test
    @DisplayName("markNoBids: CLOSED 경매를 FAILED(유찰)로 저장하고 winner는 비운 채 둔다")
    void markNoBids_closedBecomesFailed() {
        Auction auction = closedAuction();
        when(auctionRepository.findById(AUCTION_ID)).thenReturn(Optional.of(auction));

        service.markNoBids(AUCTION_ID);

        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.FAILED);
        assertThat(auction.getWinnerId()).isNull();
        verify(auctionRepository).save(auction);
    }

    @Test
    @DisplayName("markNoBids: CLOSED가 아니면 예외, 저장 없음")
    void markNoBids_nonClosedThrows() {
        Auction auction = activeAuction();
        when(auctionRepository.findById(AUCTION_ID)).thenReturn(Optional.of(auction));

        assertThatThrownBy(() -> service.markNoBids(AUCTION_ID))
                .isInstanceOf(IllegalStateException.class);
        verify(auctionRepository, never()).save(any());
    }

    // ---------- assignWinnerForPayment ----------

    @Test
    @DisplayName("assignWinnerForPayment: winner·winningPrice를 저장하고 CLOSED를 유지한다")
    void assignWinnerForPayment_assignsWinnerKeepsClosed() {
        Auction auction = closedAuction();
        when(auctionRepository.findById(AUCTION_ID)).thenReturn(Optional.of(auction));

        service.assignWinnerForPayment(AUCTION_ID, WINNER_ID, PRICE);

        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.CLOSED);
        assertThat(auction.getWinnerId()).isEqualTo(WINNER_ID);
        assertThat(auction.getWinningPrice()).isEqualByComparingTo(PRICE);
        verify(auctionRepository).save(auction);
    }

    @Test
    @DisplayName("assignWinnerForPayment: CLOSED가 아닌 경매에는 낙찰자를 지정할 수 없다")
    void assignWinnerForPayment_nonClosedThrows() {
        Auction auction = activeAuction();
        when(auctionRepository.findById(AUCTION_ID)).thenReturn(Optional.of(auction));

        assertThatThrownBy(() -> service.assignWinnerForPayment(AUCTION_ID, WINNER_ID, PRICE))
                .isInstanceOf(IllegalStateException.class);
        assertThat(auction.getWinnerId()).isNull();
        verify(auctionRepository, never()).save(any());
    }

    // ---------- completeAuction ----------

    @Test
    @DisplayName("completeAuction: CLOSED 경매를 COMPLETED로 전이한다")
    void completeAuction_closedBecomesCompleted() {
        Auction auction = closedAuction();
        when(auctionRepository.findById(AUCTION_ID)).thenReturn(Optional.of(auction));

        service.completeAuction(AUCTION_ID);

        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.COMPLETED);
        verify(auctionRepository).save(auction);
    }

    @Test
    @DisplayName("completeAuction: 이미 COMPLETED이면 무시한다(멱등)")
    void completeAuction_alreadyCompletedIsIdempotent() {
        Auction auction = closedAuction();
        auction.complete();
        when(auctionRepository.findById(AUCTION_ID)).thenReturn(Optional.of(auction));

        service.completeAuction(AUCTION_ID);

        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.COMPLETED);
        verify(auctionRepository, never()).save(any());
    }

    @Test
    @DisplayName("completeAuction: ACTIVE 경매에 complete()하면 예외")
    void completeAuction_activeThrows() {
        Auction auction = activeAuction();
        when(auctionRepository.findById(AUCTION_ID)).thenReturn(Optional.of(auction));

        assertThatThrownBy(() -> service.completeAuction(AUCTION_ID))
                .isInstanceOf(IllegalStateException.class);
        verify(auctionRepository, never()).save(any());
    }
}
