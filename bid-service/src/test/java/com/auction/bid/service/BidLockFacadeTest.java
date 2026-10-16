package com.auction.bid.service;

import com.auction.bid.client.AuctionSummaryResponse;
import com.auction.bid.domain.Bid;
import com.auction.bid.dto.PlaceBidRequest;
import com.auction.bid.exception.BidConflictException;
import com.auction.bid.exception.SellerCannotBidException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BidLockFacadeTest {

    private static final Long AUCTION_ID = 10L;
    private static final Long BIDDER_ID = 2L;

    @Mock private RedissonClient redissonClient;
    @Mock private BidService bidService;
    @Mock private RLock rLock;

    private BidLockFacade facade;

    @BeforeEach
    void setUp() {
        facade = new BidLockFacade(redissonClient, bidService);
    }

    // equals가 없는 DTO라 같은 인스턴스가 그대로 넘어가는지로 검증한다.
    private final AuctionSummaryResponse auction = new AuctionSummaryResponse(
            AUCTION_ID, 1L, new BigDecimal("10000"), "ACTIVE", LocalDateTime.of(2026, 9, 16, 13, 0));

    private AuctionSummaryResponse auction() {
        return auction;
    }

    /** 경매 조회·검사(prepareBid)는 락 전에 통과한 것으로 둔다. lenient: 락 획득 전에 끝나는 테스트도 있다. */
    private void stubPrepared() {
        lenient().when(bidService.prepareBid(eq(BIDDER_ID), any(PlaceBidRequest.class))).thenReturn(auction());
    }

    private PlaceBidRequest request() {
        PlaceBidRequest req = new PlaceBidRequest();
        req.setAuctionId(AUCTION_ID);
        req.setAmount(new BigDecimal("15000"));
        return req;
    }

    @Test
    @DisplayName("락 획득 성공 시 BidService.placeBid를 호출하고 결과를 반환한다")
    void lockAcquiredDelegatesToService() throws InterruptedException {
        stubPrepared();
        when(redissonClient.getLock("bid:auction:" + AUCTION_ID)).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        Bid expected = new Bid(AUCTION_ID, BIDDER_ID, new BigDecimal("15000"));
        when(bidService.placeBid(eq(BIDDER_ID), any(PlaceBidRequest.class), any(AuctionSummaryResponse.class))).thenReturn(expected);

        Bid result = facade.placeBidWithLock(BIDDER_ID, request());

        assertThat(result).isSameAs(expected);
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("락 획득 실패 시 BID_CONFLICT(409)를 던지고 BidService를 호출하지 않는다")
    void lockNotAcquiredThrowsConflict() throws InterruptedException {
        stubPrepared();
        when(redissonClient.getLock("bid:auction:" + AUCTION_ID)).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        assertThatThrownBy(() -> facade.placeBidWithLock(BIDDER_ID, request()))
                .isInstanceOf(BidConflictException.class);
        verify(bidService, never()).placeBid(any(), any(), any());
    }

    @Test
    @DisplayName("InterruptedException 발생 시 BID_CONFLICT를 던지고 인터럽트 플래그를 복원한다")
    void interruptedThrowsConflict() throws InterruptedException {
        stubPrepared();
        when(redissonClient.getLock("bid:auction:" + AUCTION_ID)).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException());

        assertThatThrownBy(() -> facade.placeBidWithLock(BIDDER_ID, request()))
                .isInstanceOf(BidConflictException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();

        // 후속 테스트에 영향 주지 않도록 인터럽트 클리어
        Thread.interrupted();
    }

    @Test
    @DisplayName("BidService 예외 발생 시에도 락이 해제된다")
    void lockReleasedOnServiceException() throws InterruptedException {
        stubPrepared();
        when(redissonClient.getLock("bid:auction:" + AUCTION_ID)).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(bidService.placeBid(eq(BIDDER_ID), any(PlaceBidRequest.class), any(AuctionSummaryResponse.class)))
                .thenThrow(new RuntimeException("DB error"));

        assertThatThrownBy(() -> facade.placeBidWithLock(BIDDER_ID, request()))
                .isInstanceOf(RuntimeException.class);
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("경매 조회·검사(prepareBid)는 락을 잡기 전에 하고, 그 결과를 트랜잭션 단계에 넘긴다")
    void preparesBeforeLockAndPassesAuction() throws InterruptedException {
        stubPrepared();
        when(redissonClient.getLock("bid:auction:" + AUCTION_ID)).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        facade.placeBidWithLock(BIDDER_ID, request());

        InOrder inOrder = inOrder(bidService, rLock);
        inOrder.verify(bidService).prepareBid(eq(BIDDER_ID), any(PlaceBidRequest.class));
        inOrder.verify(rLock).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
        inOrder.verify(bidService).placeBid(eq(BIDDER_ID), any(PlaceBidRequest.class), same(auction()));
        inOrder.verify(rLock).unlock();
    }

    @Test
    @DisplayName("prepareBid에서 거절되면 락을 잡지 않는다 (판매자 본인·상태 위반·상류 장애가 락 대기열에 서지 않는다)")
    void rejectedBeforeLockDoesNotTouchLock() {
        when(bidService.prepareBid(eq(BIDDER_ID), any(PlaceBidRequest.class)))
                .thenThrow(new SellerCannotBidException(AUCTION_ID));

        assertThatThrownBy(() -> facade.placeBidWithLock(BIDDER_ID, request()))
                .isInstanceOf(SellerCannotBidException.class);
        verifyNoInteractions(redissonClient);
        verify(bidService, never()).placeBid(any(), any(), any());
    }
}
