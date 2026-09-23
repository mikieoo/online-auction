package com.auction.auction.controller;

import com.auction.auction.dto.AuctionResponse;
import com.auction.auction.dto.CreateAuctionRequest;
import com.auction.auction.event.AuctionEventProducer;
import com.auction.auction.service.AuctionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/auctions")
public class AuctionController {

    private final AuctionService auctionService;
    private final AuctionEventProducer eventProducer;

    public AuctionController(AuctionService auctionService, AuctionEventProducer eventProducer) {
        this.auctionService = auctionService;
        this.eventProducer = eventProducer;
    }

    @PostMapping
    public ResponseEntity<AuctionResponse> createAuction(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody CreateAuctionRequest request) {
        AuctionResponse response = auctionService.createAuction(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{auctionId}/start")
    public ResponseEntity<AuctionResponse> startAuction(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long auctionId) {
        AuctionResponse response = auctionService.startAuction(userId, auctionId);
        eventProducer.publishStarted(response);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{auctionId}")
    public ResponseEntity<AuctionResponse> getAuction(@PathVariable Long auctionId) {
        return ResponseEntity.ok(auctionService.getAuction(auctionId));
    }

    @GetMapping
    public ResponseEntity<List<AuctionResponse>> getAllAuctions() {
        return ResponseEntity.ok(auctionService.getAllAuctions());
    }
}
