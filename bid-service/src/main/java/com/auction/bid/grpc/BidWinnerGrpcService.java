package com.auction.bid.grpc;

import com.auction.bid.dto.BidResponse;
import com.auction.bid.dto.WinnerResponse;
import com.auction.bid.service.BidService;
import com.auction.common.grpc.BidWinnerServiceGrpc;
import com.auction.common.grpc.ConfirmWinnerRequest;
import com.auction.common.grpc.ConfirmWinnerResponse;
import com.auction.common.grpc.WinningBid;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 낙찰 확정 gRPC 서버. 기존 REST {@code POST /internal/v1/bids/auctions/{id}/winner}와
 * 동일한 비즈니스 로직({@link BidService#confirmWinner})을 gRPC로 노출한다.
 * REST 엔드포인트(InternalBidController)는 그대로 유지한다 — 디버깅·curl 테스트에 쓸 수 있다.
 */
@GrpcService
public class BidWinnerGrpcService extends BidWinnerServiceGrpc.BidWinnerServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(BidWinnerGrpcService.class);

    private final BidService bidService;

    public BidWinnerGrpcService(BidService bidService) {
        this.bidService = bidService;
    }

    @Override
    public void confirmWinner(ConfirmWinnerRequest request,
                              StreamObserver<ConfirmWinnerResponse> responseObserver) {
        try {
            WinnerResponse result = bidService.confirmWinner(request.getAuctionId());

            ConfirmWinnerResponse.Builder builder = ConfirmWinnerResponse.newBuilder()
                    .setAuctionId(result.getAuctionId())
                    .setHasBids(result.isHasBids());

            if (result.isHasBids() && result.getWinningBid() != null) {
                BidResponse bid = result.getWinningBid();
                WinningBid.Builder bidBuilder = WinningBid.newBuilder()
                        .setBidderId(bid.getBidderId())
                        .setAmount(bid.getAmount().toPlainString())
                        .setStatus(bid.getStatus().name());
                if (bid.getBidId() != null) bidBuilder.setBidId(bid.getBidId());
                if (bid.getAuctionId() != null) bidBuilder.setAuctionId(bid.getAuctionId());
                if (bid.getCreatedAt() != null) bidBuilder.setCreatedAt(bid.getCreatedAt().toString());
                builder.setWinningBid(bidBuilder.build());
            }

            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("gRPC confirmWinner 처리 실패. auctionId={}", request.getAuctionId(), e);
            responseObserver.onError(
                    Status.INTERNAL.withDescription(e.getMessage()).withCause(e).asRuntimeException());
        }
    }
}
