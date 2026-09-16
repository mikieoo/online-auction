package com.auction.bid.controller;

import com.auction.bid.dto.BidResponse;
import com.auction.bid.dto.PlaceBidRequest;
import com.auction.bid.service.BidService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/bids")
public class BidController {

    private final BidService bidService;

    public BidController(BidService bidService) {
        this.bidService = bidService;
    }

    @PostMapping
    public ResponseEntity<BidResponse> placeBid(
            @RequestHeader("X-User-Id") Long bidderId,
            @Valid @RequestBody PlaceBidRequest request) {
        var bid = bidService.placeBid(bidderId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(BidResponse.from(bid));
    }

    @GetMapping
    public ResponseEntity<List<BidResponse>> getBidsByAuction(@RequestParam("auctionId") Long auctionId) {
        var bids = bidService.getBidsByAuction(auctionId).stream()
                .map(BidResponse::from)
                .toList();
        return ResponseEntity.ok(bids);
    }

    @GetMapping("/{bidId}")
    public ResponseEntity<BidResponse> getBid(@PathVariable Long bidId) {
        var bid = bidService.getBid(bidId);
        return ResponseEntity.ok(BidResponse.from(bid));
    }
}
