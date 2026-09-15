package com.auction.auction.service;

import com.auction.auction.domain.Auction;
import com.auction.auction.domain.AuctionStatus;
import com.auction.auction.dto.CreateAuctionRequest;
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
    public Auction createAuction(Long sellerId, CreateAuctionRequest request) {
        var product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "상품을 찾을 수 없습니다. id=" + request.getProductId()));

        if (!product.getSellerId().equals(sellerId)) {
            throw new IllegalArgumentException("본인의 상품만 경매에 등록할 수 있습니다.");
        }

        if (request.getEndTime().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("종료 시간은 현재 시간 이후여야 합니다.");
        }

        boolean hasActiveAuction = auctionRepository.existsByProductIdAndStatus(
                request.getProductId(), AuctionStatus.ACTIVE);
        if (hasActiveAuction) {
            throw new IllegalStateException(
                    "해당 상품에 이미 진행 중인 경매가 있습니다. productId=" + request.getProductId());
        }

        Auction auction = new Auction(request.getProductId(), request.getEndTime());
        return auctionRepository.save(auction);
    }

    @Transactional
    public Auction startAuction(Long sellerId, Long auctionId) {
        Auction auction = getAuction(auctionId);

        var product = productRepository.findById(auction.getProductId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "상품을 찾을 수 없습니다. id=" + auction.getProductId()));

        if (!product.getSellerId().equals(sellerId)) {
            throw new IllegalArgumentException("본인의 경매만 시작할 수 있습니다.");
        }

        boolean hasActiveAuction = auctionRepository.existsByProductIdAndStatus(
                auction.getProductId(), AuctionStatus.ACTIVE);
        if (hasActiveAuction) {
            throw new IllegalStateException(
                    "해당 상품에 이미 진행 중인 경매가 있습니다.");
        }

        auction.start();
        return auctionRepository.save(auction);
    }

    @Transactional(readOnly = true)
    public Auction getAuction(Long auctionId) {
        return auctionRepository.findById(auctionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "경매를 찾을 수 없습니다. id=" + auctionId));
    }

    @Transactional(readOnly = true)
    public List<Auction> getAllAuctions() {
        return auctionRepository.findAll();
    }
}
