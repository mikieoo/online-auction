package com.auction.bid.controller;

import com.auction.bid.domain.Bid;
import com.auction.bid.domain.BidStatus;
import com.auction.bid.dto.PlaceBidRequest;
import com.auction.bid.dto.WinnerResponse;
import com.auction.bid.exception.AuctionAlreadyEndedException;
import com.auction.bid.exception.AuctionNotActiveException;
import com.auction.bid.exception.AuctionNotFoundException;
import com.auction.bid.exception.BidAmountTooLowException;
import com.auction.bid.exception.BidNotFoundException;
import com.auction.bid.exception.GlobalExceptionHandler;
import com.auction.bid.exception.SellerCannotBidException;
import com.auction.bid.exception.UpstreamErrorException;
import com.auction.bid.exception.UpstreamUnavailableException;
import com.auction.bid.service.BidService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BidControllerTest {

    private static final String BODY = "{\"auctionId\": 10, \"amount\": 15000}";

    @Mock
    private BidService bidService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new BidController(bidService), new InternalBidController(bidService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private Bid bidWithId(Long id, Long bidderId, String amount) {
        Bid bid = new Bid(10L, bidderId, new BigDecimal(amount));
        ReflectionTestUtils.setField(bid, "bidId", id);
        return bid;
    }

    @Test
    @DisplayName("POST /api/v1/bids → 201 BidResponse")
    void placeBidCreated() throws Exception {
        when(bidService.placeBid(eq(2L), any(PlaceBidRequest.class)))
                .thenReturn(bidWithId(1L, 2L, "15000"));

        mockMvc.perform(post("/api/v1/bids")
                        .header("X-User-Id", 2L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bidId").value(1))
                .andExpect(jsonPath("$.auctionId").value(10))
                .andExpect(jsonPath("$.bidderId").value(2))
                .andExpect(jsonPath("$.amount").value(15000))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    @DisplayName("X-User-Id 헤더 누락 → 400 INVALID_REQUEST")
    void missingHeader() throws Exception {
        mockMvc.perform(post("/api/v1/bids")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("amount 누락 → 400 INVALID_REQUEST (Bean Validation)")
    void missingAmount() throws Exception {
        mockMvc.perform(post("/api/v1/bids")
                        .header("X-User-Id", 2L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"auctionId\": 10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("amount가 0 이하 → 400 INVALID_REQUEST")
    void nonPositiveAmount() throws Exception {
        mockMvc.perform(post("/api/v1/bids")
                        .header("X-User-Id", 2L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"auctionId\": 10, \"amount\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("본문 파싱 실패 → 400 INVALID_REQUEST")
    void malformedBody() throws Exception {
        mockMvc.perform(post("/api/v1/bids")
                        .header("X-User-Id", 2L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("시작가 미만 → 400 BID_AMOUNT_TOO_LOW")
    void bidAmountTooLow() throws Exception {
        when(bidService.placeBid(eq(2L), any(PlaceBidRequest.class)))
                .thenThrow(BidAmountTooLowException.belowStartingPrice(
                        new BigDecimal("15000"), new BigDecimal("20000")));

        mockMvc.perform(post("/api/v1/bids")
                        .header("X-User-Id", 2L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BID_AMOUNT_TOO_LOW"));
    }

    @Test
    @DisplayName("판매자 본인 → 403 SELLER_CANNOT_BID")
    void sellerCannotBid() throws Exception {
        when(bidService.placeBid(eq(2L), any(PlaceBidRequest.class)))
                .thenThrow(new SellerCannotBidException(10L));

        mockMvc.perform(post("/api/v1/bids")
                        .header("X-User-Id", 2L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELLER_CANNOT_BID"));
    }

    @Test
    @DisplayName("경매 없음 → 404 AUCTION_NOT_FOUND")
    void auctionNotFound() throws Exception {
        when(bidService.placeBid(eq(2L), any(PlaceBidRequest.class)))
                .thenThrow(new AuctionNotFoundException(10L));

        mockMvc.perform(post("/api/v1/bids")
                        .header("X-User-Id", 2L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUCTION_NOT_FOUND"));
    }

    @Test
    @DisplayName("비ACTIVE 경매 → 409 AUCTION_NOT_ACTIVE")
    void auctionNotActive() throws Exception {
        when(bidService.placeBid(eq(2L), any(PlaceBidRequest.class)))
                .thenThrow(new AuctionNotActiveException(10L, "WAITING"));

        mockMvc.perform(post("/api/v1/bids")
                        .header("X-User-Id", 2L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUCTION_NOT_ACTIVE"));
    }

    @Test
    @DisplayName("endTime 경과 → 409 AUCTION_ALREADY_ENDED")
    void auctionAlreadyEnded() throws Exception {
        when(bidService.placeBid(eq(2L), any(PlaceBidRequest.class)))
                .thenThrow(new AuctionAlreadyEndedException(10L));

        mockMvc.perform(post("/api/v1/bids")
                        .header("X-User-Id", 2L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUCTION_ALREADY_ENDED"));
    }

    @Test
    @DisplayName("auction-service 연결 실패 → 503 UPSTREAM_UNAVAILABLE")
    void upstreamUnavailable() throws Exception {
        when(bidService.placeBid(eq(2L), any(PlaceBidRequest.class)))
                .thenThrow(new UpstreamUnavailableException("연결 실패"));

        mockMvc.perform(post("/api/v1/bids")
                        .header("X-User-Id", 2L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"));
    }

    @Test
    @DisplayName("auction-service 기타 4xx → 502 UPSTREAM_ERROR")
    void upstreamError() throws Exception {
        when(bidService.placeBid(eq(2L), any(PlaceBidRequest.class)))
                .thenThrow(new UpstreamErrorException("upstream 400"));

        mockMvc.perform(post("/api/v1/bids")
                        .header("X-User-Id", 2L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("UPSTREAM_ERROR"));
    }

    @Test
    @DisplayName("남은 IllegalStateException → 409 INVALID_STATE_TRANSITION")
    void illegalStateMapped() throws Exception {
        when(bidService.placeBid(eq(2L), any(PlaceBidRequest.class)))
                .thenThrow(new IllegalStateException("잘못된 상태 전이"));

        mockMvc.perform(post("/api/v1/bids")
                        .header("X-User-Id", 2L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("낙관적 락 충돌 → 409 BID_CONFLICT")
    void optimisticLockConflict() throws Exception {
        when(bidService.placeBid(eq(2L), any(PlaceBidRequest.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Bid.class.getName(), 1L));

        mockMvc.perform(post("/api/v1/bids")
                        .header("X-User-Id", 2L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BID_CONFLICT"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("예상치 못한 예외 → 500 INTERNAL_ERROR")
    void unexpectedMapped() throws Exception {
        when(bidService.placeBid(eq(2L), any(PlaceBidRequest.class)))
                .thenThrow(new RuntimeException("boom"));

        mockMvc.perform(post("/api/v1/bids")
                        .header("X-User-Id", 2L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    @DisplayName("GET /api/v1/bids?auctionId= → 200 amount 내림차순 목록")
    void listBidsByAuction() throws Exception {
        when(bidService.getBidsByAuction(10L))
                .thenReturn(List.of(bidWithId(2L, 3L, "20000"), bidWithId(1L, 2L, "15000")));

        mockMvc.perform(get("/api/v1/bids").param("auctionId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].amount").value(20000))
                .andExpect(jsonPath("$[1].amount").value(15000));
    }

    @Test
    @DisplayName("GET /api/v1/bids/{bidId} → 200")
    void getBid() throws Exception {
        when(bidService.getBid(1L)).thenReturn(bidWithId(1L, 2L, "15000"));

        mockMvc.perform(get("/api/v1/bids/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bidId").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("GET /api/v1/bids/{bidId} 없음 → 404 BID_NOT_FOUND")
    void getBidNotFound() throws Exception {
        when(bidService.getBid(99L)).thenThrow(new BidNotFoundException(99L));

        mockMvc.perform(get("/api/v1/bids/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BID_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /internal/v1/bids/auctions/{id}/winner → 200 WinnerResponse (낙찰자 있음)")
    void confirmWinnerWithBids() throws Exception {
        Bid winner = bidWithId(5L, 2L, "30000");
        winner.markWinner();
        when(bidService.confirmWinner(10L)).thenReturn(WinnerResponse.of(10L, winner));

        mockMvc.perform(post("/internal/v1/bids/auctions/10/winner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auctionId").value(10))
                .andExpect(jsonPath("$.hasBids").value(true))
                .andExpect(jsonPath("$.winningBid.bidId").value(5))
                .andExpect(jsonPath("$.winningBid.status").value("WINNER"));
    }

    @Test
    @DisplayName("POST /internal/v1/bids/auctions/{id}/winner → 200 hasBids=false, winningBid=null (입찰 없음)")
    void confirmWinnerNoBids() throws Exception {
        when(bidService.confirmWinner(10L)).thenReturn(WinnerResponse.noBids(10L));

        mockMvc.perform(post("/internal/v1/bids/auctions/10/winner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auctionId").value(10))
                .andExpect(jsonPath("$.hasBids").value(false))
                .andExpect(jsonPath("$.winningBid").value(nullValue()));
    }
}
