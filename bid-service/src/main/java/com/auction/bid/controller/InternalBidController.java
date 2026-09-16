package com.auction.bid.controller;

import com.auction.bid.dto.WinnerResponse;
import com.auction.bid.service.BidService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 내부 호출 전용. Gateway에 노출하지 않는다.
 */
@RestController
@RequestMapping("/internal/v1/bids")
public class InternalBidController {

    private final BidService bidService;

    public InternalBidController(BidService bidService) {
        this.bidService = bidService;
    }

    @PostMapping("/auctions/{auctionId}/winner")
    public ResponseEntity<WinnerResponse> confirmWinner(@PathVariable Long auctionId) {
        return ResponseEntity.ok(bidService.confirmWinner(auctionId));
    }
}
