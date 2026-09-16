package com.auction.auction.service;

import com.auction.auction.domain.Auction;
import com.auction.auction.domain.AuctionStatus;
import com.auction.auction.domain.Product;
import com.auction.auction.dto.AuctionResponse;
import com.auction.auction.dto.CreateAuctionRequest;
import com.auction.auction.exception.ConflictException;
import com.auction.auction.exception.ForbiddenException;
import com.auction.auction.exception.InvalidRequestException;
import com.auction.auction.exception.NotFoundException;
import com.auction.auction.repository.AuctionRepository;
import com.auction.auction.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AuctionService {

    private final AuctionRepository auctionRepository;
    private final ProductRepository productRepository;

    public AuctionService(AuctionRepository auctionRepository,
                          ProductRepository productRepository) {
        this.auctionRepository = auctionRepository;
        this.productRepository = productRepository;
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

        // 종료 시간이 이미 지난 경매를 시작하면 스케줄러가 즉시 마감하게 되므로 시작 자체를 거부한다.
        if (!auction.getEndTime().isAfter(LocalDateTime.now())) {
            throw new ConflictException("AUCTION_ALREADY_ENDED",
                    "이미 종료 시간이 지난 경매는 시작할 수 없습니다. endTime=" + auction.getEndTime());
        }

        ensureNoActiveAuction(auction.getProductId());

        auction.start();
        Auction saved = auctionRepository.save(auction);
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

    /** 하나의 Product에 ACTIVE Auction은 최대 1개 — MySQL은 partial unique index가 없어 애플리케이션에서 검증. */
    private void ensureNoActiveAuction(Long productId) {
        if (auctionRepository.existsByProductIdAndStatus(productId, AuctionStatus.ACTIVE)) {
            throw new ConflictException("ACTIVE_AUCTION_EXISTS",
                    "해당 상품에 이미 진행 중인 경매가 있습니다. productId=" + productId);
        }
    }
}
