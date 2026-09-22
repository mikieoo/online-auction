package com.auction.bid.grpc;

import com.auction.bid.domain.BidStatus;
import com.auction.bid.dto.BidResponse;
import com.auction.bid.dto.WinnerResponse;
import com.auction.bid.service.BidService;
import com.auction.common.grpc.ConfirmWinnerRequest;
import com.auction.common.grpc.ConfirmWinnerResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BidWinnerGrpcServiceTest {

    @Mock
    private BidService bidService;

    @Mock
    private StreamObserver<ConfirmWinnerResponse> responseObserver;

    @InjectMocks
    private BidWinnerGrpcService grpcService;

    private static final Long AUCTION_ID = 1L;

    @Test
    @DisplayName("낙찰자가 있으면 hasBids=true와 winningBid 정보를 응답한다")
    void confirmWinner_withBids() {
        BidResponse bidResponse = stubBidResponse(500L, AUCTION_ID, 77L,
                new BigDecimal("15000.00"), BidStatus.WINNER);
        WinnerResponse winnerResponse = WinnerResponse.of(AUCTION_ID,
                stubBid(500L, AUCTION_ID, 77L, new BigDecimal("15000.00"), BidStatus.WINNER));
        when(bidService.confirmWinner(AUCTION_ID)).thenReturn(winnerResponse);

        grpcService.confirmWinner(request(AUCTION_ID), responseObserver);

        ArgumentCaptor<ConfirmWinnerResponse> captor = ArgumentCaptor.forClass(ConfirmWinnerResponse.class);
        verify(responseObserver).onNext(captor.capture());
        verify(responseObserver).onCompleted();

        ConfirmWinnerResponse response = captor.getValue();
        assertThat(response.getAuctionId()).isEqualTo(AUCTION_ID);
        assertThat(response.getHasBids()).isTrue();
        assertThat(response.hasWinningBid()).isTrue();
        assertThat(response.getWinningBid().getBidderId()).isEqualTo(77L);
        assertThat(response.getWinningBid().getAmount()).isEqualTo("15000.00");
        assertThat(response.getWinningBid().getStatus()).isEqualTo("WINNER");
    }

    @Test
    @DisplayName("입찰이 없으면 hasBids=false, winningBid 없이 응답한다")
    void confirmWinner_noBids() {
        when(bidService.confirmWinner(AUCTION_ID)).thenReturn(WinnerResponse.noBids(AUCTION_ID));

        grpcService.confirmWinner(request(AUCTION_ID), responseObserver);

        ArgumentCaptor<ConfirmWinnerResponse> captor = ArgumentCaptor.forClass(ConfirmWinnerResponse.class);
        verify(responseObserver).onNext(captor.capture());
        verify(responseObserver).onCompleted();

        ConfirmWinnerResponse response = captor.getValue();
        assertThat(response.getAuctionId()).isEqualTo(AUCTION_ID);
        assertThat(response.getHasBids()).isFalse();
        assertThat(response.hasWinningBid()).isFalse();
    }

    @Test
    @DisplayName("BidService에서 예외가 나면 INTERNAL 상태로 에러를 응답한다")
    void confirmWinner_serviceException() {
        when(bidService.confirmWinner(AUCTION_ID)).thenThrow(new RuntimeException("DB 오류"));

        grpcService.confirmWinner(request(AUCTION_ID), responseObserver);

        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(responseObserver).onError(captor.capture());

        Throwable error = captor.getValue();
        assertThat(error).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) error).getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
    }

    private static ConfirmWinnerRequest request(Long auctionId) {
        return ConfirmWinnerRequest.newBuilder().setAuctionId(auctionId).build();
    }

    /** 테스트용 Bid 스텁 — BidResponse.from()에 필요한 최소 필드만 제공한다. */
    private static com.auction.bid.domain.Bid stubBid(Long bidId, Long auctionId, Long bidderId,
                                                       BigDecimal amount, BidStatus status) {
        com.auction.bid.domain.Bid bid = new com.auction.bid.domain.Bid(auctionId, bidderId, amount);
        if (status == BidStatus.WINNER) {
            bid.markWinner();
        }
        return bid;
    }

    /** 사용하지 않지만 테스트 가독성을 위해 남긴다. */
    private static BidResponse stubBidResponse(Long bidId, Long auctionId, Long bidderId,
                                                BigDecimal amount, BidStatus status) {
        return BidResponse.from(stubBid(bidId, auctionId, bidderId, amount, status));
    }
}
