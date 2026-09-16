package com.auction.bid.repository;

import com.auction.bid.domain.Bid;
import com.auction.bid.domain.BidStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BidRepository extends JpaRepository<Bid, Long> {

    List<Bid> findByAuctionIdOrderByAmountDesc(Long auctionId);

    /**
     * 한 경매에 ACTIVE(또는 WINNER) Bid는 최대 1개라는 불변 조건 위에서 동작한다.
     */
    Optional<Bid> findByAuctionIdAndStatus(Long auctionId, BidStatus status);
}
