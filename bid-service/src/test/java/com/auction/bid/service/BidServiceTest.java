package com.auction.bid.service;

import com.auction.bid.client.AuctionClient;
import com.auction.bid.client.AuctionSummaryResponse;
import com.auction.bid.domain.Bid;
import com.auction.bid.domain.BidStatus;
import com.auction.bid.dto.PlaceBidRequest;
import com.auction.bid.dto.WinnerResponse;
import com.auction.bid.exception.AuctionAlreadyEndedException;
import com.auction.bid.exception.AuctionNotActiveException;
import com.auction.bid.exception.AuctionNotFoundException;
import com.auction.bid.exception.BidAmountTooLowException;
import com.auction.bid.exception.BidNotFoundException;
import com.auction.bid.exception.InvalidRequestException;
import com.auction.bid.exception.SellerCannotBidException;
import com.auction.bid.exception.UpstreamErrorException;
import com.auction.bid.exception.UpstreamUnavailableException;
import com.auction.bid.repository.BidRepository;
import feign.FeignException;
import feign.RetryableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BidServiceTest {

    private static final long INCREMENT = 1000L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant FIXED_NOW = Instant.parse("2026-09-16T03:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_NOW, ZONE);

    private static final Long AUCTION_ID = 10L;
    private static final Long SELLER_ID = 1L;
    private static final Long BIDDER_ID = 2L;
    private static final BigDecimal STARTING_PRICE = new BigDecimal("10000");

    @Mock
    private BidRepository bidRepository;

    @Mock
    private AuctionClient auctionClient;

    private BidService bidService;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(FIXED_NOW, ZONE);
        bidService = new BidService(bidRepository, auctionClient, fixedClock, INCREMENT);
    }

    private AuctionSummaryResponse activeAuction() {
        return new AuctionSummaryResponse(AUCTION_ID, SELLER_ID, STARTING_PRICE, "ACTIVE", NOW.plusHours(1));
    }

    private PlaceBidRequest request(String amount) {
        PlaceBidRequest request = new PlaceBidRequest();
        request.setAuctionId(AUCTION_ID);
        request.setAmount(amount == null ? null : new BigDecimal(amount));
        return request;
    }

    private void stubSaveReturnsArgument() {
        when(bidRepository.save(any(Bid.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Nested
    @DisplayName("입찰 접수 - 금액 규칙")
    class AmountRules {

        @Test
        @DisplayName("amount가 null이면 INVALID_REQUEST로 거절하고 auction-service를 호출하지 않는다")
        void nullAmountRejected() {
            assertThatThrownBy(() -> bidService.placeBid(BIDDER_ID, request(null)))
                    .isInstanceOf(InvalidRequestException.class);
            verify(auctionClient, never()).getAuction(any());
        }

        @Test
        @DisplayName("amount가 0 이하이면 INVALID_REQUEST로 거절한다")
        void nonPositiveAmountRejected() {
            assertThatThrownBy(() -> bidService.placeBid(BIDDER_ID, request("0")))
                    .isInstanceOf(InvalidRequestException.class);
            verify(auctionClient, never()).getAuction(any());
        }

        @Test
        @DisplayName("첫 입찰이 startingPrice 미만이면 BID_AMOUNT_TOO_LOW로 거절한다")
        void firstBidBelowStartingPriceRejected() {
            when(auctionClient.getAuction(AUCTION_ID)).thenReturn(activeAuction());
            when(bidRepository.findByAuctionIdAndStatus(AUCTION_ID, BidStatus.ACTIVE)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> bidService.placeBid(BIDDER_ID, request("9999")))
                    .isInstanceOf(BidAmountTooLowException.class)
                    .satisfies(e -> assertThat(((BidAmountTooLowException) e).getCode()).isEqualTo("BID_AMOUNT_TOO_LOW"));
            verify(bidRepository, never()).save(any());
        }

        @Test
        @DisplayName("첫 입찰이 startingPrice와 같으면 허용되고 ACTIVE로 저장된다")
        void firstBidEqualToStartingPriceAccepted() {
            when(auctionClient.getAuction(AUCTION_ID)).thenReturn(activeAuction());
            when(bidRepository.findByAuctionIdAndStatus(AUCTION_ID, BidStatus.ACTIVE)).thenReturn(Optional.empty());
            stubSaveReturnsArgument();

            Bid saved = bidService.placeBid(BIDDER_ID, request("10000"));

            assertThat(saved.getStatus()).isEqualTo(BidStatus.ACTIVE);
            assertThat(saved.getAmount()).isEqualByComparingTo("10000");
            assertThat(saved.getBidderId()).isEqualTo(BIDDER_ID);
            assertThat(saved.getAuctionId()).isEqualTo(AUCTION_ID);
            verify(bidRepository, times(1)).save(any(Bid.class));
        }

        @Test
        @DisplayName("후속 입찰이 현재 최고가 + 증가 단위와 같으면 거절한다 (초과해야 함)")
        void subsequentBidEqualToThresholdRejected() {
            Bid current = new Bid(AUCTION_ID, 3L, new BigDecimal("15000"));
            when(auctionClient.getAuction(AUCTION_ID)).thenReturn(activeAuction());
            when(bidRepository.findByAuctionIdAndStatus(AUCTION_ID, BidStatus.ACTIVE)).thenReturn(Optional.of(current));

            assertThatThrownBy(() -> bidService.placeBid(BIDDER_ID, request("16000")))
                    .isInstanceOf(BidAmountTooLowException.class);
            assertThat(current.getStatus()).isEqualTo(BidStatus.ACTIVE);
            verify(bidRepository, never()).save(any());
        }

        @Test
        @DisplayName("후속 입찰이 현재 최고가 + 증가 단위를 초과하면 허용되고 이전 ACTIVE는 OUTBID가 된다")
        void subsequentBidAboveThresholdAcceptedAndPreviousOutbid() {
            Bid current = new Bid(AUCTION_ID, 3L, new BigDecimal("15000"));
            when(auctionClient.getAuction(AUCTION_ID)).thenReturn(activeAuction());
            when(bidRepository.findByAuctionIdAndStatus(AUCTION_ID, BidStatus.ACTIVE)).thenReturn(Optional.of(current));
            stubSaveReturnsArgument();

            Bid saved = bidService.placeBid(BIDDER_ID, request("16001"));

            assertThat(current.getStatus()).isEqualTo(BidStatus.OUTBID);
            assertThat(saved.getStatus()).isEqualTo(BidStatus.ACTIVE);
            assertThat(saved.getAmount()).isEqualByComparingTo("16001");

            ArgumentCaptor<Bid> captor = ArgumentCaptor.forClass(Bid.class);
            verify(bidRepository, times(2)).save(captor.capture());
            assertThat(captor.getAllValues().get(0)).isSameAs(current);
            assertThat(captor.getAllValues().get(1)).isSameAs(saved);
        }

        @Test
        @DisplayName("같은 입찰자가 다시 입찰해도 이전 ACTIVE Bid는 OUTBID가 된다 (ACTIVE 최대 1개)")
        void sameBidderRaisingOwnBidOutbidsPrevious() {
            Bid current = new Bid(AUCTION_ID, BIDDER_ID, new BigDecimal("15000"));
            when(auctionClient.getAuction(AUCTION_ID)).thenReturn(activeAuction());
            when(bidRepository.findByAuctionIdAndStatus(AUCTION_ID, BidStatus.ACTIVE)).thenReturn(Optional.of(current));
            stubSaveReturnsArgument();

            Bid saved = bidService.placeBid(BIDDER_ID, request("20000"));

            assertThat(current.getStatus()).isEqualTo(BidStatus.OUTBID);
            assertThat(saved.getStatus()).isEqualTo(BidStatus.ACTIVE);
            assertThat(saved.getBidderId()).isEqualTo(BIDDER_ID);
        }
    }

    @Nested
    @DisplayName("입찰 접수 - 경매 상태 검증")
    class AuctionStateRules {

        @Test
        @DisplayName("판매자 본인이 입찰하면 SELLER_CANNOT_BID(403)")
        void sellerCannotBid() {
            when(auctionClient.getAuction(AUCTION_ID)).thenReturn(activeAuction());

            assertThatThrownBy(() -> bidService.placeBid(SELLER_ID, request("20000")))
                    .isInstanceOf(SellerCannotBidException.class);
            verify(bidRepository, never()).findByAuctionIdAndStatus(any(), any());
            verify(bidRepository, never()).save(any());
        }

        @Test
        @DisplayName("경매가 ACTIVE가 아니면 AUCTION_NOT_ACTIVE(409)")
        void nonActiveAuctionRejected() {
            when(auctionClient.getAuction(AUCTION_ID)).thenReturn(
                    new AuctionSummaryResponse(AUCTION_ID, SELLER_ID, STARTING_PRICE, "WAITING", NOW.plusHours(1)));

            assertThatThrownBy(() -> bidService.placeBid(BIDDER_ID, request("20000")))
                    .isInstanceOf(AuctionNotActiveException.class);
            verify(bidRepository, never()).save(any());
        }

        @Test
        @DisplayName("ACTIVE여도 endTime이 현재 시각 이하이면 AUCTION_ALREADY_ENDED(409)")
        void endedAuctionRejected() {
            when(auctionClient.getAuction(AUCTION_ID)).thenReturn(
                    new AuctionSummaryResponse(AUCTION_ID, SELLER_ID, STARTING_PRICE, "ACTIVE", NOW));

            assertThatThrownBy(() -> bidService.placeBid(BIDDER_ID, request("20000")))
                    .isInstanceOf(AuctionAlreadyEndedException.class);
            verify(bidRepository, never()).save(any());
        }

        @Test
        @DisplayName("판매자 검사는 상태 검사보다 먼저 수행된다")
        void sellerCheckPrecedesStatusCheck() {
            when(auctionClient.getAuction(AUCTION_ID)).thenReturn(
                    new AuctionSummaryResponse(AUCTION_ID, SELLER_ID, STARTING_PRICE, "CLOSED", NOW.minusHours(1)));

            assertThatThrownBy(() -> bidService.placeBid(SELLER_ID, request("20000")))
                    .isInstanceOf(SellerCannotBidException.class);
        }
    }

    @Nested
    @DisplayName("입찰 접수 - auction-service 연동 실패")
    class UpstreamFailures {

        @Test
        @DisplayName("경매가 없으면(upstream 404 → ErrorDecoder) AUCTION_NOT_FOUND(404)가 그대로 전달된다")
        void auctionNotFoundPropagates() {
            when(auctionClient.getAuction(AUCTION_ID)).thenThrow(new AuctionNotFoundException(AUCTION_ID));

            assertThatThrownBy(() -> bidService.placeBid(BIDDER_ID, request("20000")))
                    .isInstanceOf(AuctionNotFoundException.class);
            verify(bidRepository, never()).save(any());
        }

        @Test
        @DisplayName("연결 실패·타임아웃(RetryableException)은 UPSTREAM_UNAVAILABLE(503)로 변환된다")
        void connectionFailureMappedTo503() {
            RetryableException connectionRefused = mock(RetryableException.class);
            when(auctionClient.getAuction(AUCTION_ID)).thenThrow(connectionRefused);

            assertThatThrownBy(() -> bidService.placeBid(BIDDER_ID, request("20000")))
                    .isInstanceOf(UpstreamUnavailableException.class)
                    .satisfies(e -> assertThat(((UpstreamUnavailableException) e).getCode())
                            .isEqualTo("UPSTREAM_UNAVAILABLE"));
            verify(bidRepository, never()).save(any());
        }

        @Test
        @DisplayName("기타 FeignException도 UPSTREAM_UNAVAILABLE(503)로 변환된다")
        void genericFeignExceptionMappedTo503() {
            FeignException feignException = mock(FeignException.class);
            when(auctionClient.getAuction(AUCTION_ID)).thenThrow(feignException);

            assertThatThrownBy(() -> bidService.placeBid(BIDDER_ID, request("20000")))
                    .isInstanceOf(UpstreamUnavailableException.class);
        }

        @Test
        @DisplayName("ErrorDecoder가 만든 UPSTREAM_ERROR(502)는 재변환 없이 전달된다")
        void upstreamErrorPropagates() {
            when(auctionClient.getAuction(AUCTION_ID)).thenThrow(new UpstreamErrorException("bad request"));

            assertThatThrownBy(() -> bidService.placeBid(BIDDER_ID, request("20000")))
                    .isInstanceOf(UpstreamErrorException.class);
        }
    }

    @Nested
    @DisplayName("낙찰 확정")
    class ConfirmWinner {

        @Test
        @DisplayName("ACTIVE Bid가 있으면 WINNER로 전이하고 반환한다")
        void activeBecomesWinner() {
            Bid active = new Bid(AUCTION_ID, BIDDER_ID, new BigDecimal("20000"));
            when(bidRepository.findByAuctionIdAndStatus(AUCTION_ID, BidStatus.WINNER)).thenReturn(Optional.empty());
            when(bidRepository.findByAuctionIdAndStatus(AUCTION_ID, BidStatus.ACTIVE)).thenReturn(Optional.of(active));
            stubSaveReturnsArgument();

            WinnerResponse response = bidService.confirmWinner(AUCTION_ID);

            assertThat(active.getStatus()).isEqualTo(BidStatus.WINNER);
            assertThat(response.getAuctionId()).isEqualTo(AUCTION_ID);
            assertThat(response.isHasBids()).isTrue();
            assertThat(response.getWinningBid()).isNotNull();
            assertThat(response.getWinningBid().getStatus()).isEqualTo(BidStatus.WINNER);
            assertThat(response.getWinningBid().getBidderId()).isEqualTo(BIDDER_ID);
            verify(bidRepository, times(1)).save(active);
        }

        @Test
        @DisplayName("이미 WINNER가 있으면 같은 Bid를 그대로 반환한다 (멱등)")
        void repeatedCallReturnsSameWinner() {
            Bid winner = new Bid(AUCTION_ID, BIDDER_ID, new BigDecimal("20000"));
            winner.markWinner();
            when(bidRepository.findByAuctionIdAndStatus(AUCTION_ID, BidStatus.WINNER)).thenReturn(Optional.of(winner));

            WinnerResponse first = bidService.confirmWinner(AUCTION_ID);
            WinnerResponse second = bidService.confirmWinner(AUCTION_ID);

            assertThat(first.isHasBids()).isTrue();
            assertThat(second.isHasBids()).isTrue();
            assertThat(first.getWinningBid().getStatus()).isEqualTo(BidStatus.WINNER);
            assertThat(second.getWinningBid().getAmount()).isEqualByComparingTo(first.getWinningBid().getAmount());
            verify(bidRepository, never()).findByAuctionIdAndStatus(AUCTION_ID, BidStatus.ACTIVE);
            verify(bidRepository, never()).save(any());
        }

        @Test
        @DisplayName("ACTIVE도 WINNER도 없으면 hasBids=false, winningBid=null을 반환한다")
        void noBidsReturnsHasBidsFalse() {
            when(bidRepository.findByAuctionIdAndStatus(AUCTION_ID, BidStatus.WINNER)).thenReturn(Optional.empty());
            when(bidRepository.findByAuctionIdAndStatus(AUCTION_ID, BidStatus.ACTIVE)).thenReturn(Optional.empty());

            WinnerResponse response = bidService.confirmWinner(AUCTION_ID);

            assertThat(response.getAuctionId()).isEqualTo(AUCTION_ID);
            assertThat(response.isHasBids()).isFalse();
            assertThat(response.getWinningBid()).isNull();
            verify(bidRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("조회")
    class Queries {

        @Test
        @DisplayName("존재하지 않는 bidId 조회는 BID_NOT_FOUND")
        void getBidNotFound() {
            when(bidRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> bidService.getBid(99L))
                    .isInstanceOf(BidNotFoundException.class);
        }

        @Test
        @DisplayName("경매별 입찰 목록은 amount 내림차순 리포지토리 메서드를 사용한다")
        void getBidsByAuctionDelegatesToRepository() {
            Bid high = new Bid(AUCTION_ID, 3L, new BigDecimal("20000"));
            Bid low = new Bid(AUCTION_ID, 2L, new BigDecimal("10000"));
            when(bidRepository.findByAuctionIdOrderByAmountDesc(AUCTION_ID)).thenReturn(List.of(high, low));

            List<Bid> bids = bidService.getBidsByAuction(AUCTION_ID);

            assertThat(bids).containsExactly(high, low);
        }
    }
}
