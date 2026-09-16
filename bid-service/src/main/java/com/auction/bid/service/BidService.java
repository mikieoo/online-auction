package com.auction.bid.service;

import com.auction.bid.client.AuctionClient;
import com.auction.bid.client.AuctionSummaryResponse;
import com.auction.bid.domain.Bid;
import com.auction.bid.domain.BidStatus;
import com.auction.bid.dto.PlaceBidRequest;
import com.auction.bid.dto.WinnerResponse;
import com.auction.bid.exception.AuctionAlreadyEndedException;
import com.auction.bid.exception.AuctionNotActiveException;
import com.auction.bid.exception.BidAmountTooLowException;
import com.auction.bid.exception.BidException;
import com.auction.bid.exception.BidNotFoundException;
import com.auction.bid.exception.InvalidRequestException;
import com.auction.bid.exception.SellerCannotBidException;
import com.auction.bid.exception.UpstreamErrorException;
import com.auction.bid.exception.UpstreamUnavailableException;
import com.auction.bid.repository.BidRepository;
import feign.FeignException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class BidService {

    private static final String AUCTION_STATUS_ACTIVE = "ACTIVE";

    private final BidRepository bidRepository;
    private final AuctionClient auctionClient;
    private final Clock clock;
    private final BigDecimal bidIncrement;

    public BidService(BidRepository bidRepository,
                      AuctionClient auctionClient,
                      Clock clock,
                      @Value("${bid.increment:1000}") long bidIncrement) {
        this.bidRepository = bidRepository;
        this.auctionClient = auctionClient;
        this.clock = clock;
        this.bidIncrement = BigDecimal.valueOf(bidIncrement);
    }

    /**
     * 입찰 접수. 검사 순서: 금액 형식 → 경매 조회 → 판매자 본인 → 경매 상태/마감 → 금액 규칙 → 저장.
     * 첫 실패에서 즉시 거절한다.
     *
     * 동시성: 같은 경매에 대한 동시 입찰은 D7에서 auctionId 기준 분산 락(Redisson)으로 직렬화할 예정.
     * D3에서는 락 없이 동작하므로 동시 요청 시 ACTIVE Bid 유일성이 깨질 수 있음을 알고 있다.
     *
     * 트랜잭션 경계: auction-service 조회(Feign)가 이 트랜잭션 안에서 일어난다. 요청 1건당 조회 1회이고 쓰기 전 검증에
     * 필요한 값이라 의도적으로 한 트랜잭션에 두었다(정산 스케줄러처럼 다건을 순회하는 경로와 다르다). D7에서 분산 락을
     * 도입할 때 락 획득 → 조회 → 트랜잭션 쓰기 순서로 재구성한다.
     */
    @Transactional
    public Bid placeBid(Long bidderId, PlaceBidRequest request) {
        if (bidderId == null) {
            throw new InvalidRequestException("X-User-Id 헤더는 필수입니다.");
        }
        if (request.getAuctionId() == null) {
            throw new InvalidRequestException("auctionId는 필수입니다.");
        }
        BigDecimal amount = request.getAmount();
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidRequestException("amount는 필수이며 0보다 커야 합니다.");
        }

        Long auctionId = request.getAuctionId();
        AuctionSummaryResponse auction = fetchAuction(auctionId);
        if (auction.getSellerId() == null || auction.getStartingPrice() == null) {
            // 계약상 필수 필드가 비어 있으면 검증을 건너뛰지 않고 상류 오류로 거절한다 (본인 입찰·시작가 검사가 조용히 통과되는 것을 막는다).
            throw new UpstreamErrorException(
                    "auction-service 응답에 sellerId/startingPrice가 없습니다. auctionId=" + auctionId);
        }

        if (bidderId.equals(auction.getSellerId())) {
            throw new SellerCannotBidException(auctionId);
        }

        if (!AUCTION_STATUS_ACTIVE.equals(auction.getStatus())) {
            throw new AuctionNotActiveException(auctionId, auction.getStatus());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (auction.getEndTime() != null && !auction.getEndTime().isAfter(now)) {
            throw new AuctionAlreadyEndedException(auctionId);
        }

        Optional<Bid> currentActive = bidRepository.findByAuctionIdAndStatus(auctionId, BidStatus.ACTIVE);
        if (currentActive.isEmpty()) {
            BigDecimal startingPrice = auction.getStartingPrice();
            if (amount.compareTo(startingPrice) < 0) {
                throw BidAmountTooLowException.belowStartingPrice(amount, startingPrice);
            }
        } else {
            BigDecimal currentAmount = currentActive.get().getAmount();
            BigDecimal threshold = currentAmount.add(bidIncrement);
            if (amount.compareTo(threshold) <= 0) {
                throw BidAmountTooLowException.belowIncrement(amount, currentAmount, bidIncrement);
            }
        }

        // 같은 입찰자가 금액을 올리는 경우에도 이전 ACTIVE Bid는 OUTBID로 내린다 (ACTIVE 최대 1개 불변 조건).
        currentActive.ifPresent(previous -> {
            previous.markOutbid();
            bidRepository.save(previous);
        });

        return bidRepository.save(new Bid(auctionId, bidderId, amount));
    }

    @Transactional(readOnly = true)
    public Bid getBid(Long bidId) {
        return bidRepository.findById(bidId)
                .orElseThrow(() -> new BidNotFoundException(bidId));
    }

    @Transactional(readOnly = true)
    public List<Bid> getBidsByAuction(Long auctionId) {
        return bidRepository.findByAuctionIdOrderByAmountDesc(auctionId);
    }

    /**
     * 낙찰 확정(내부 API). ACTIVE → WINNER 전이. 이미 WINNER가 있으면 그대로 반환(멱등).
     * 입찰이 없으면 hasBids=false로 반환하며 HTTP 오류로 표현하지 않는다.
     */
    @Transactional
    public WinnerResponse confirmWinner(Long auctionId) {
        Optional<Bid> existingWinner = bidRepository.findByAuctionIdAndStatus(auctionId, BidStatus.WINNER);
        if (existingWinner.isPresent()) {
            return WinnerResponse.of(auctionId, existingWinner.get());
        }

        Optional<Bid> active = bidRepository.findByAuctionIdAndStatus(auctionId, BidStatus.ACTIVE);
        if (active.isEmpty()) {
            return WinnerResponse.noBids(auctionId);
        }

        Bid winner = active.get();
        winner.markWinner();
        return WinnerResponse.of(auctionId, bidRepository.save(winner));
    }

    private AuctionSummaryResponse fetchAuction(Long auctionId) {
        try {
            AuctionSummaryResponse auction = auctionClient.getAuction(auctionId);
            if (auction == null) {
                throw new UpstreamUnavailableException("auction-service 응답이 비어 있습니다. auctionId=" + auctionId);
            }
            return auction;
        } catch (BidException e) {
            // ErrorDecoder가 이미 변환한 예외(404/5xx/4xx)는 그대로 전달
            throw e;
        } catch (FeignException e) {
            // 연결 거부·타임아웃(RetryableException 포함)은 ErrorDecoder를 거치지 않으므로 여기서 503으로 변환
            throw new UpstreamUnavailableException(
                    "auction-service에 연결할 수 없습니다. auctionId=" + auctionId, e);
        }
    }
}
