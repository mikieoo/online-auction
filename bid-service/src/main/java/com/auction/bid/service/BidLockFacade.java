package com.auction.bid.service;

import com.auction.bid.domain.Bid;
import com.auction.bid.dto.PlaceBidRequest;
import com.auction.bid.exception.BidConflictException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * auctionId 기준 분산 락(Redisson)으로 입찰을 직렬화하는 Facade.
 *
 * 락 획득 → BidService.placeBid(트랜잭션) → 락 해제 순서로 동작한다.
 * 락이 트랜잭션 바깥에 있으므로, 트랜잭션 커밋까지 보호된다.
 *
 * @Version 낙관적 락은 안전망으로 유지한다(Redis 장애 시 DB 레벨에서 잡아냄).
 */
@Component
public class BidLockFacade {

    private static final Logger log = LoggerFactory.getLogger(BidLockFacade.class);
    private static final String LOCK_KEY_PREFIX = "bid:auction:";
    private static final long WAIT_TIME_SECONDS = 3;
    private static final long LEASE_TIME_SECONDS = 5;

    private final RedissonClient redissonClient;
    private final BidService bidService;

    public BidLockFacade(RedissonClient redissonClient, BidService bidService) {
        this.redissonClient = redissonClient;
        this.bidService = bidService;
    }

    public Bid placeBidWithLock(Long bidderId, PlaceBidRequest request) {
        Long auctionId = request.getAuctionId();
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + auctionId);

        boolean acquired = false;
        try {
            acquired = lock.tryLock(WAIT_TIME_SECONDS, LEASE_TIME_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("분산 락 획득 실패. auctionId={}", auctionId);
                throw new BidConflictException(auctionId);
            }
            return bidService.placeBid(bidderId, request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BidConflictException(auctionId);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
