package com.auction.auction.service;

import com.auction.auction.domain.Auction;
import com.auction.auction.domain.AuctionStatus;
import com.auction.auction.domain.Product;
import com.auction.auction.dto.AuctionResponse;
import com.auction.auction.dto.CreateAuctionRequest;
import com.auction.auction.exception.AuctionServiceException;
import com.auction.auction.exception.ConflictException;
import com.auction.auction.exception.ForbiddenException;
import com.auction.auction.exception.InvalidRequestException;
import com.auction.auction.exception.NotFoundException;
import com.auction.auction.repository.AuctionRepository;
import com.auction.auction.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuctionServiceTest {

    private static final Long SELLER_ID = 10L;
    private static final Long OTHER_USER_ID = 99L;
    private static final Long PRODUCT_ID = 1L;
    private static final Long AUCTION_ID = 100L;

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private ProductRepository productRepository;

    private AuctionService auctionService;

    private Product product;

    @BeforeEach
    void setUp() {
        auctionService = new AuctionService(auctionRepository, productRepository);
        product = new Product(SELLER_ID, "노트북", "설명", new BigDecimal("50000.00"));
        ReflectionTestUtils.setField(product, "productId", PRODUCT_ID);
    }

    private Auction auctionWithEndTime(LocalDateTime endTime) {
        Auction auction = new Auction(PRODUCT_ID, endTime);
        ReflectionTestUtils.setField(auction, "auctionId", AUCTION_ID);
        return auction;
    }

    private static void assertErrorCode(Throwable t, Class<? extends AuctionServiceException> type, String code) {
        assertThat(t).isInstanceOf(type);
        assertThat(((AuctionServiceException) t).getCode()).isEqualTo(code);
    }

    // ---------- startAuction ----------

    @Test
    @DisplayName("startAuction: endTime이 이미 지났으면 409 AUCTION_ALREADY_ENDED")
    void startAuction_endTimePassed_conflict() {
        Auction auction = auctionWithEndTime(LocalDateTime.now().minusMinutes(1));
        when(auctionRepository.findById(AUCTION_ID)).thenReturn(Optional.of(auction));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        Throwable t = catchThrowable(() -> auctionService.startAuction(SELLER_ID, AUCTION_ID));

        assertErrorCode(t, ConflictException.class, "AUCTION_ALREADY_ENDED");
        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.WAITING);
        verify(auctionRepository, never()).save(any());
    }

    @Test
    @DisplayName("startAuction: 타인의 경매는 403 FORBIDDEN")
    void startAuction_otherUser_forbidden() {
        Auction auction = auctionWithEndTime(LocalDateTime.now().plusHours(1));
        when(auctionRepository.findById(AUCTION_ID)).thenReturn(Optional.of(auction));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        Throwable t = catchThrowable(() -> auctionService.startAuction(OTHER_USER_ID, AUCTION_ID));

        assertErrorCode(t, ForbiddenException.class, "FORBIDDEN");
    }

    @Test
    @DisplayName("startAuction: WAITING이 아니면 409 INVALID_STATE_TRANSITION")
    void startAuction_notWaiting_conflict() {
        Auction auction = auctionWithEndTime(LocalDateTime.now().plusHours(1));
        auction.start();
        when(auctionRepository.findById(AUCTION_ID)).thenReturn(Optional.of(auction));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        Throwable t = catchThrowable(() -> auctionService.startAuction(SELLER_ID, AUCTION_ID));

        assertErrorCode(t, ConflictException.class, "INVALID_STATE_TRANSITION");
    }

    @Test
    @DisplayName("startAuction: 상품에 ACTIVE 경매가 있으면 409 ACTIVE_AUCTION_EXISTS")
    void startAuction_activeExists_conflict() {
        Auction auction = auctionWithEndTime(LocalDateTime.now().plusHours(1));
        when(auctionRepository.findById(AUCTION_ID)).thenReturn(Optional.of(auction));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(auctionRepository.existsByProductIdAndStatus(PRODUCT_ID, AuctionStatus.ACTIVE)).thenReturn(true);

        Throwable t = catchThrowable(() -> auctionService.startAuction(SELLER_ID, AUCTION_ID));

        assertErrorCode(t, ConflictException.class, "ACTIVE_AUCTION_EXISTS");
    }

    @Test
    @DisplayName("startAuction: 경매가 없으면 404 AUCTION_NOT_FOUND")
    void startAuction_notFound() {
        when(auctionRepository.findById(AUCTION_ID)).thenReturn(Optional.empty());

        Throwable t = catchThrowable(() -> auctionService.startAuction(SELLER_ID, AUCTION_ID));

        assertErrorCode(t, NotFoundException.class, "AUCTION_NOT_FOUND");
    }

    @Test
    @DisplayName("startAuction: 정상 시작 시 ACTIVE로 전이되고 sellerId/startingPrice가 응답에 포함된다")
    void startAuction_success() {
        Auction auction = auctionWithEndTime(LocalDateTime.now().plusHours(1));
        when(auctionRepository.findById(AUCTION_ID)).thenReturn(Optional.of(auction));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(auctionRepository.existsByProductIdAndStatus(PRODUCT_ID, AuctionStatus.ACTIVE)).thenReturn(false);
        when(auctionRepository.save(auction)).thenReturn(auction);

        AuctionResponse response = auctionService.startAuction(SELLER_ID, AUCTION_ID);

        assertThat(response.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(response.getAuctionId()).isEqualTo(AUCTION_ID);
        assertThat(response.getSellerId()).isEqualTo(SELLER_ID);
        assertThat(response.getStartingPrice()).isEqualByComparingTo("50000.00");
    }

    // ---------- createAuction ----------

    private CreateAuctionRequest createRequest(LocalDateTime endTime) {
        CreateAuctionRequest request = new CreateAuctionRequest();
        request.setProductId(PRODUCT_ID);
        request.setEndTime(endTime);
        return request;
    }

    @Test
    @DisplayName("createAuction: 상품이 없으면 404 PRODUCT_NOT_FOUND")
    void createAuction_productNotFound() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        Throwable t = catchThrowable(
                () -> auctionService.createAuction(SELLER_ID, createRequest(LocalDateTime.now().plusHours(1))));

        assertErrorCode(t, NotFoundException.class, "PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("createAuction: 타인 상품이면 403 FORBIDDEN")
    void createAuction_otherUser_forbidden() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        Throwable t = catchThrowable(
                () -> auctionService.createAuction(OTHER_USER_ID, createRequest(LocalDateTime.now().plusHours(1))));

        assertErrorCode(t, ForbiddenException.class, "FORBIDDEN");
    }

    @Test
    @DisplayName("createAuction: 종료 시간이 과거면 400 INVALID_REQUEST")
    void createAuction_endTimePast_invalid() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        Throwable t = catchThrowable(
                () -> auctionService.createAuction(SELLER_ID, createRequest(LocalDateTime.now().minusMinutes(1))));

        assertErrorCode(t, InvalidRequestException.class, "INVALID_REQUEST");
    }

    @Test
    @DisplayName("createAuction: ACTIVE 경매가 있으면 409 ACTIVE_AUCTION_EXISTS")
    void createAuction_activeExists_conflict() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(auctionRepository.existsByProductIdAndStatus(PRODUCT_ID, AuctionStatus.ACTIVE)).thenReturn(true);

        Throwable t = catchThrowable(
                () -> auctionService.createAuction(SELLER_ID, createRequest(LocalDateTime.now().plusHours(1))));

        assertErrorCode(t, ConflictException.class, "ACTIVE_AUCTION_EXISTS");
    }

    @Test
    @DisplayName("createAuction: 정상 생성 시 WAITING 상태와 sellerId/startingPrice가 응답에 포함된다")
    void createAuction_success() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(auctionRepository.existsByProductIdAndStatus(PRODUCT_ID, AuctionStatus.ACTIVE)).thenReturn(false);
        when(auctionRepository.save(any(Auction.class))).thenAnswer(inv -> {
            Auction saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "auctionId", AUCTION_ID);
            return saved;
        });

        AuctionResponse response = auctionService.createAuction(
                SELLER_ID, createRequest(LocalDateTime.now().plusHours(1)));

        assertThat(response.getAuctionId()).isEqualTo(AUCTION_ID);
        assertThat(response.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(response.getStatus()).isEqualTo(AuctionStatus.WAITING);
        assertThat(response.getSellerId()).isEqualTo(SELLER_ID);
        assertThat(response.getStartingPrice()).isEqualByComparingTo("50000.00");
    }

    // ---------- getAuction / getAllAuctions ----------

    @Test
    @DisplayName("getAuction: 없으면 404 AUCTION_NOT_FOUND")
    void getAuction_notFound() {
        when(auctionRepository.findById(AUCTION_ID)).thenReturn(Optional.empty());

        Throwable t = catchThrowable(() -> auctionService.getAuction(AUCTION_ID));

        assertErrorCode(t, NotFoundException.class, "AUCTION_NOT_FOUND");
    }

    @Test
    @DisplayName("getAuction: 응답에 sellerId/startingPrice가 채워진다")
    void getAuction_includesProductFields() {
        Auction auction = auctionWithEndTime(LocalDateTime.now().plusHours(1));
        when(auctionRepository.findById(AUCTION_ID)).thenReturn(Optional.of(auction));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        AuctionResponse response = auctionService.getAuction(AUCTION_ID);

        assertThat(response.getSellerId()).isEqualTo(SELLER_ID);
        assertThat(response.getStartingPrice()).isEqualByComparingTo("50000.00");
    }

    @Test
    @DisplayName("getAllAuctions: 각 항목에 sellerId/startingPrice가 채워진다")
    void getAllAuctions_includesProductFields() {
        Auction auction = auctionWithEndTime(LocalDateTime.now().plusHours(1));
        when(auctionRepository.findAll()).thenReturn(List.of(auction));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        List<AuctionResponse> responses = auctionService.getAllAuctions();

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getSellerId()).isEqualTo(SELLER_ID);
        assertThat(responses.get(0).getStartingPrice()).isEqualByComparingTo("50000.00");
    }
}
