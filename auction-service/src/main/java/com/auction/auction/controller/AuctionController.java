package com.auction.auction.controller;

import com.auction.auction.dto.AuctionResponse;
import com.auction.auction.dto.CreateAuctionRequest;
import com.auction.auction.service.AuctionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/auctions")
public class AuctionController {

    private final AuctionService auctionService;

    public AuctionController(AuctionService auctionService) {
        this.auctionService = auctionService;
    }

    @PostMapping
    public ResponseEntity<AuctionResponse> createAuction(
            @RequestHeader("X-User-Id") Long userId,
            @RequestBody CreateAuctionRequest request) {
        var auction = auctionService.createAuction(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AuctionResponse.from(auction));
    }

    @PatchMapping("/{auctionId}/start")
    public ResponseEntity<AuctionResponse> startAuction(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long auctionId) {
        var auction = auctionService.startAuction(userId, auctionId);
        return ResponseEntity.ok(AuctionResponse.from(auction));
    }

    @GetMapping("/{auctionId}")
    public ResponseEntity<AuctionResponse> getAuction(@PathVariable Long auctionId) {
        var auction = auctionService.getAuction(auctionId);
        return ResponseEntity.ok(AuctionResponse.from(auction));
    }

    @GetMapping
    public ResponseEntity<List<AuctionResponse>> getAllAuctions() {
        var auctions = auctionService.getAllAuctions().stream()
                .map(AuctionResponse::from)
                .toList();
        return ResponseEntity.ok(auctions);
    }
}
