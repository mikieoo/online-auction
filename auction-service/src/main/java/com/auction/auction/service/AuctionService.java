package com.auction.auction.service;

import com.auction.auction.domain.Auction;
import com.auction.auction.domain.AuctionStatus;
import com.auction.auction.domain.Product;
import com.auction.auction.dto.AuctionResponse;
import com.auction.auction.dto.CreateAuctionRequest;
import com.auction.auction.event.OutboxEventRecorder;
import com.auction.auction.exception.ConflictException;
import com.auction.auction.exception.ForbiddenException;
import com.auction.auction.exception.InvalidRequestException;
import com.auction.auction.exception.NotFoundException;
import com.auction.auction.repository.AuctionRepository;
import com.auction.auction.repository.ProductRepository;
import com.auction.common.event.AuctionStartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class AuctionService {

    private static final Logger log = LoggerFactory.getLogger(AuctionService.class);
    static final String ACTIVE_PRODUCT_CONSTRAINT = "uk_auction_active_product";

    private final AuctionRepository auctionRepository;
    private final ProductRepository productRepository;
    private final OutboxEventRecorder outboxRecorder;

    public AuctionService(AuctionRepository auctionRepository,
                          ProductRepository productRepository,
                          OutboxEventRecorder outboxRecorder) {
        this.auctionRepository = auctionRepository;
        this.productRepository = productRepository;
        this.outboxRecorder = outboxRecorder;
    }

    @Transactional
    public AuctionResponse createAuction(Long sellerId, CreateAuctionRequest request) {
        Product product = findProduct(request.getProductId());

        if (!product.getSellerId().equals(sellerId)) {
            throw new ForbiddenException("본인의 상품만 경매에 등록할 수 있습니다.");
        }

        if (request.getEndTime().isBefore(LocalDateTime.now())) {
            throw new InvalidRequestException("종료 시간은 현재 시간 이후여야 합니다.");
        }

        ensureNoActiveAuction(product.getProductId());

        Auction auction = auctionRepository.save(new Auction(product.getProductId(), request.getEndTime()));
        return AuctionResponse.from(auction, product);
    }

    /**
     * WAITING → ACTIVE 전이 + AuctionStartedEvent outbox 기록.
     * 트랜잭션 안에서 outbox INSERT가 함께 커밋된다.
     */
    @Transactional
    public AuctionResponse startAuction(Long sellerId, Long auctionId) {
        Auction auction = findAuction(auctionId);
        Product product = findProduct(auction.getProductId());

        if (!product.getSellerId().equals(sellerId)) {
            throw new ForbiddenException("본인의 경매만 시작할 수 있습니다.");
        }

        if (auction.getStatus() != AuctionStatus.WAITING) {
            throw new ConflictException("INVALID_STATE_TRANSITION",
                    "경매를 시작할 수 없습니다. 현재 상태: " + auction.getStatus());
        }

        if (!auction.getEndTime().isAfter(LocalDateTime.now())) {
            throw new ConflictException("AUCTION_ALREADY_ENDED",
                    "이미 종료 시간이 지난 경매는 시작할 수 없습니다. endTime=" + auction.getEndTime());
        }

        // 빠른 거절과 명확한 오류용 사전 검사. 동시에 시작한 두 요청은 둘 다 통과할 수 있고,
        // 그때는 uk_auction_active_product(상품당 ACTIVE 1개) 제약이 한쪽을 막는다.
        ensureNoActiveAuction(auction.getProductId());

        auction.start();
        Auction saved = startWithUniquenessGuard(auction);

        AuctionStartedEvent event = new AuctionStartedEvent(
                UUID.randomUUID().toString(), LocalDateTime.now(),
                saved.getAuctionId(), product.getProductId(),
                product.getStartingPrice(), saved.getEndTime());
        outboxRecorder.record("Auction", saved.getAuctionId(), event);

        return AuctionResponse.from(saved, product);
    }

    @Transactional(readOnly = true)
    public AuctionResponse getAuction(Long auctionId) {
        Auction auction = findAuction(auctionId);
        Product product = findProduct(auction.getProductId());
        return AuctionResponse.from(auction, product);
    }

    /** 목록 조회는 항목마다 Product를 개별 조회한다 (N+1 허용). */
    @Transactional(readOnly = true)
    public List<AuctionResponse> getAllAuctions() {
        return auctionRepository.findAll().stream()
                .map(auction -> AuctionResponse.from(auction, findProduct(auction.getProductId())))
                .toList();
    }

    private Auction findAuction(Long auctionId) {
        return auctionRepository.findById(auctionId)
                .orElseThrow(() -> new NotFoundException("AUCTION_NOT_FOUND",
                        "경매를 찾을 수 없습니다. id=" + auctionId));
    }

    private Product findProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new NotFoundException("PRODUCT_NOT_FOUND",
                        "상품을 찾을 수 없습니다. id=" + productId));
    }

    /**
     * 시작(WAITING → ACTIVE)을 즉시 flush해 제약 위반을 이 자리에서 받는다. 그냥 save하면 UPDATE가 커밋 시점으로
     * 미뤄져, 위반이 트랜잭션 프록시 밖에서 DataIntegrityViolationException(500)으로 나간다.
     */
    private Auction startWithUniquenessGuard(Auction auction) {
        try {
            return auctionRepository.saveAndFlush(auction);
        } catch (DataIntegrityViolationException e) {
            if (violates(e, ACTIVE_PRODUCT_CONSTRAINT)) {
                log.warn("동시 시작을 DB 제약이 막음(사전 검사 통과 후 경합). productId={}, auctionId={}",
                        auction.getProductId(), auction.getAuctionId());
                throw activeAuctionExists(auction.getProductId());
            }
            throw e;
        }
    }

    /** 원인 메시지 예: Duplicate entry '5' for key 'auction.uk_auction_active_product' */
    private static boolean violates(DataIntegrityViolationException e, String constraint) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.contains(constraint);
    }

    private static ConflictException activeAuctionExists(Long productId) {
        return new ConflictException("ACTIVE_AUCTION_EXISTS",
                "해당 상품에 이미 진행 중인 경매가 있습니다. productId=" + productId);
    }

    /** 하나의 Product에 ACTIVE Auction은 최대 1개. 최종 방어는 DB 제약(uk_auction_active_product)이다. */
    private void ensureNoActiveAuction(Long productId) {
        if (auctionRepository.existsByProductIdAndStatus(productId, AuctionStatus.ACTIVE)) {
            throw activeAuctionExists(productId);
        }
    }
}
