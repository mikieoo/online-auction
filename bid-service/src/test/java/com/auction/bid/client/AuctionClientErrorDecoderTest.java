package com.auction.bid.client;

import com.auction.bid.exception.AuctionNotFoundException;
import com.auction.bid.exception.UpstreamErrorException;
import com.auction.bid.exception.UpstreamUnavailableException;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuctionClientErrorDecoderTest {

    private final AuctionClientErrorDecoder decoder = new AuctionClientErrorDecoder();

    private Response response(int status) {
        Request request = Request.create(
                Request.HttpMethod.GET,
                "http://localhost:8081/api/v1/auctions/42",
                Map.of(),
                null,
                StandardCharsets.UTF_8,
                new RequestTemplate());
        return Response.builder()
                .status(status)
                .reason("")
                .headers(Map.of())
                .request(request)
                .build();
    }

    @Test
    @DisplayName("upstream 404 → AuctionNotFoundException(AUCTION_NOT_FOUND, 404)")
    void notFound() {
        Exception e = decoder.decode("AuctionClient#getAuction(Long)", response(404));

        assertThat(e).isInstanceOf(AuctionNotFoundException.class);
        AuctionNotFoundException ex = (AuctionNotFoundException) e;
        assertThat(ex.getCode()).isEqualTo("AUCTION_NOT_FOUND");
        assertThat(ex.getStatus().value()).isEqualTo(404);
        assertThat(ex.getMessage()).contains("42");
    }

    @Test
    @DisplayName("upstream 5xx → UpstreamUnavailableException(UPSTREAM_UNAVAILABLE, 503)")
    void serverError() {
        Exception e = decoder.decode("AuctionClient#getAuction(Long)", response(500));

        assertThat(e).isInstanceOf(UpstreamUnavailableException.class);
        assertThat(((UpstreamUnavailableException) e).getStatus().value()).isEqualTo(503);
    }

    @Test
    @DisplayName("upstream 503 → UpstreamUnavailableException")
    void serviceUnavailable() {
        Exception e = decoder.decode("AuctionClient#getAuction(Long)", response(503));

        assertThat(e).isInstanceOf(UpstreamUnavailableException.class);
    }

    @Test
    @DisplayName("upstream 기타 4xx → UpstreamErrorException(UPSTREAM_ERROR, 502)")
    void otherClientError() {
        Exception e = decoder.decode("AuctionClient#getAuction(Long)", response(400));

        assertThat(e).isInstanceOf(UpstreamErrorException.class);
        assertThat(((UpstreamErrorException) e).getCode()).isEqualTo("UPSTREAM_ERROR");
        assertThat(((UpstreamErrorException) e).getStatus().value()).isEqualTo(502);
    }
}
