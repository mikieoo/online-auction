package com.auction.auction.client;

import com.auction.auction.config.BidClientConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * bid-service 낙찰 확정 클라이언트. auction-service는 bid_db를 직접 읽지 않고 이 HTTP 계약만 사용한다.
 * 입찰이 없는 경우도 200 + hasBids=false로 표현되므로 404를 "입찰 없음"으로 해석하지 않는다.
 * 호출은 멱등하다(같은 경매에 재호출하면 같은 WINNER를 돌려준다).
 * url이 비어 있으면(기본) name으로 Eureka 조회 + Spring Cloud LoadBalancer를 쓴다. clients.bid-service.url을 주면 디스커버리 없이 그 주소로 직접 호출한다.
 */
@FeignClient(name = "bid-service", url = "${clients.bid-service.url:}",
        configuration = BidClientConfig.class)
public interface BidClient {

    @PostMapping("/internal/v1/bids/auctions/{auctionId}/winner")
    WinnerResponse confirmWinner(@PathVariable("auctionId") Long auctionId);
}
