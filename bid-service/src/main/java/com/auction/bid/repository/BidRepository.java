package com.auction.bid.repository;

import com.auction.bid.domain.Bid;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BidRepository extends JpaRepository<Bid, Long> {

    List<Bid> findByAuctionIdOrderByAmountDesc(Long auctionId);

    Optional<Bid> findFirstByAuctionIdOrderByAmountDesc(Long auctionId);
}
