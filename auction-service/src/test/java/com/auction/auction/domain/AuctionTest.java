package com.auction.auction.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuctionTest {

    private Auction waitingAuction() {
        return new Auction(1L, LocalDateTime.now().plusHours(1));
    }

    private Auction activeAuction() {
        Auction auction = waitingAuction();
        auction.start();
        return auction;
    }

    private Auction closedAuction() {
        Auction auction = activeAuction();
        auction.close();
        return auction;
    }

    @Test
    @DisplayName("CLOSED 상태에서 assignWinner 호출 시 낙찰자와 낙찰가가 설정된다")
    void assignWinner_closed_setsWinner() {
        Auction auction = closedAuction();

        auction.assignWinner(42L, new BigDecimal("15000.00"));

        assertThat(auction.getWinnerId()).isEqualTo(42L);
        assertThat(auction.getWinningPrice()).isEqualByComparingTo("15000.00");
        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.CLOSED);
    }

    @Test
    @DisplayName("WAITING 상태에서 assignWinner 호출 시 IllegalStateException")
    void assignWinner_waiting_throws() {
        Auction auction = waitingAuction();

        assertThatThrownBy(() -> auction.assignWinner(42L, BigDecimal.TEN))
                .isInstanceOf(IllegalStateException.class);
        assertThat(auction.getWinnerId()).isNull();
    }

    @Test
    @DisplayName("ACTIVE 상태에서 assignWinner 호출 시 IllegalStateException")
    void assignWinner_active_throws() {
        Auction auction = activeAuction();

        assertThatThrownBy(() -> auction.assignWinner(42L, BigDecimal.TEN))
                .isInstanceOf(IllegalStateException.class);
        assertThat(auction.getWinnerId()).isNull();
    }

    @Test
    @DisplayName("COMPLETED 상태에서 assignWinner 호출 시 IllegalStateException")
    void assignWinner_completed_throws() {
        Auction auction = closedAuction();
        auction.complete();

        assertThatThrownBy(() -> auction.assignWinner(42L, BigDecimal.TEN))
                .isInstanceOf(IllegalStateException.class);
        assertThat(auction.getWinnerId()).isNull();
    }

    @Test
    @DisplayName("FAILED 상태에서 assignWinner 호출 시 IllegalStateException")
    void assignWinner_failed_throws() {
        Auction auction = closedAuction();
        auction.fail();

        assertThatThrownBy(() -> auction.assignWinner(42L, BigDecimal.TEN))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("상태 전이 가드: WAITING이 아니면 start 불가, ACTIVE가 아니면 close 불가, CLOSED가 아니면 complete/fail 불가")
    void transitionGuards() {
        assertThatThrownBy(() -> activeAuction().start()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> waitingAuction().close()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> activeAuction().complete()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> waitingAuction().fail()).isInstanceOf(IllegalStateException.class);
    }
}
