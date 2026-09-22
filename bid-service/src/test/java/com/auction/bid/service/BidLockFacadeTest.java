package com.auction.bid.service;

import com.auction.bid.domain.Bid;
import com.auction.bid.dto.PlaceBidRequest;
import com.auction.bid.exception.BidConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    private PlaceBidRequest request() {
        PlaceBidRequest req = new PlaceBidRequest();
        req.setAuctionId(AUCTION_ID);
        req.setAmount(new BigDecimal("15000"));
        return req;
    }

    @Test
    @DisplayName("락 획득 성공 시 BidService.placeBid를 호출하고 결과를 반환한다")
    void lockAcquiredDelegatesToService() throws InterruptedException {
        when(redissonClient.getLock("bid:auction:" + AUCTION_ID)).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        Bid expected = new Bid(AUCTION_ID, BIDDER_ID, new BigDecimal("15000"));
        when(bidService.placeBid(eq(BIDDER_ID), any(PlaceBidRequest.class))).thenReturn(expected);

        Bid result = facade.placeBidWithLock(BIDDER_ID, request());

        assertThat(result).isSameAs(expected);
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("락 획득 실패 시 BID_CONFLICT(409)를 던지고 BidService를 호출하지 않는다")
    void lockNotAcquiredThrowsConflict() throws InterruptedException {
        when(redissonClient.getLock("bid:auction:" + AUCTION_ID)).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        assertThatThrownBy(() -> facade.placeBidWithLock(BIDDER_ID, request()))
                .isInstanceOf(BidConflictException.class);
        verify(bidService, never()).placeBid(any(), any());
    }

    @Test
    @DisplayName("InterruptedException 발생 시 BID_CONFLICT를 던지고 인터럽트 플래그를 복원한다")
    void interruptedThrowsConflict() throws InterruptedException {
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
        when(redissonClient.getLock("bid:auction:" + AUCTION_ID)).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(bidService.placeBid(eq(BIDDER_ID), any(PlaceBidRequest.class)))
                .thenThrow(new RuntimeException("DB error"));

        assertThatThrownBy(() -> facade.placeBidWithLock(BIDDER_ID, request()))
                .isInstanceOf(RuntimeException.class);
        verify(rLock).unlock();
    }
}
