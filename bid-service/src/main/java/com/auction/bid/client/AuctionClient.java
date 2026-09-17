package com.auction.bid.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * auction-service 동기 조회 클라이언트. bid-service는 auction_db를 직접 읽지 않고 이 HTTP 계약만 사용한다.
 *
 * url이 비어 있으면(기본) name("auction-service")을 Eureka + Spring Cloud LoadBalancer로 해석한다.
 * clients.auction-service.url을 주면 디스커버리 없이 그 주소로 직접 호출한다(로컬 단독 실행용 선택 재정의).
 * 실패 처리(예외 매핑)는 전부 {@link AuctionClientFallbackFactory} 한 곳에 있다.
 */
@FeignClient(name = "auction-service",
        url = "${clients.auction-service.url:}",
        fallbackFactory = AuctionClientFallbackFactory.class)
public interface AuctionClient {

    @GetMapping("/api/v1/auctions/{auctionId}")
    AuctionSummaryResponse getAuction(@PathVariable("auctionId") Long auctionId);
}
