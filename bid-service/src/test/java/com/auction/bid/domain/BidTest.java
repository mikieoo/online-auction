package com.auction.bid.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BidTest {

    private Bid newBid() {
        return new Bid(1L, 2L, new BigDecimal("10000"));
    }

    @Test
    @DisplayName("새 입찰은 ACTIVE 상태이며 createdAt이 기록된다")
    void newBidIsActive() {
        Bid bid = newBid();

        assertThat(bid.getStatus()).isEqualTo(BidStatus.ACTIVE);
        assertThat(bid.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("ACTIVE → WINNER 전이는 허용된다")
    void activeToWinner() {
        Bid bid = newBid();
        bid.markWinner();
        assertThat(bid.getStatus()).isEqualTo(BidStatus.WINNER);
    }

    @Test
    @DisplayName("ACTIVE → OUTBID 전이는 허용된다")
    void activeToOutbid() {
        Bid bid = newBid();
        bid.markOutbid();
        assertThat(bid.getStatus()).isEqualTo(BidStatus.OUTBID);
    }

    @Test
    @DisplayName("OUTBID → WINNER 전이는 금지된다")
    void outbidToWinnerForbidden() {
        Bid bid = newBid();
        bid.markOutbid();

        assertThatThrownBy(bid::markWinner)
                .isInstanceOf(IllegalStateException.class);
        assertThat(bid.getStatus()).isEqualTo(BidStatus.OUTBID);
    }

    @Test
    @DisplayName("WINNER 상태에서 markWinner를 다시 호출하면 금지된다 (멱등성은 서비스 계층에서 보장)")
    void winnerToWinnerForbidden() {
        Bid bid = newBid();
        bid.markWinner();

        assertThatThrownBy(bid::markWinner)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("BidStatus는 ACTIVE, OUTBID, WINNER 세 값만 가진다")
    void statusValues() {
        assertThat(BidStatus.values())
                .containsExactlyInAnyOrder(BidStatus.ACTIVE, BidStatus.OUTBID, BidStatus.WINNER);
    }
}
