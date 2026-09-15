package com.auction.auction.repository;

import com.auction.auction.domain.Auction;
import com.auction.auction.domain.AuctionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AuctionRepository extends JpaRepository<Auction, Long> {

    boolean existsByProductIdAndStatus(Long productId, AuctionStatus status);

    List<Auction> findByStatusAndEndTimeBefore(AuctionStatus status, LocalDateTime time);
}
