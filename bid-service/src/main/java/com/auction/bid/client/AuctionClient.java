package com.auction.bid.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * auction-service 동기 조회 클라이언트. bid-service는 auction_db를 직접 읽지 않고 이 HTTP 계약만 사용한다.
 */
@FeignClient(name = "auction-service", url = "${clients.auction-service.url:http://localhost:8081}")
public interface AuctionClient {

    @GetMapping("/api/v1/auctions/{auctionId}")
    AuctionSummaryResponse getAuction(@PathVariable("auctionId") Long auctionId);
}
