package com.auction.bid.client;

import com.auction.bid.exception.AuctionNotFoundException;
import com.auction.bid.exception.UpstreamErrorException;
import com.auction.bid.exception.UpstreamUnavailableException;
import feign.Response;
import feign.codec.ErrorDecoder;

/**
 * auction-service HTTP 오류 응답을 bid-service 예외로 변환한다.
 * 404 → AUCTION_NOT_FOUND(404), 5xx → UPSTREAM_UNAVAILABLE(503), 그 외 4xx → UPSTREAM_ERROR(502).
 * 연결 실패·타임아웃은 이 디코더를 거치지 않고 feign.RetryableException으로 올라오므로 AuctionClientFallbackFactory에서 503으로 바꾼다.
 */
public class AuctionClientErrorDecoder implements ErrorDecoder {

    @Override
    public Exception decode(String methodKey, Response response) {
        int status = response.status();
        String url = response.request() != null ? response.request().url() : methodKey;

        if (status == 404) {
            return new AuctionNotFoundException(extractAuctionId(url));
        }
        if (status >= 500) {
            return new UpstreamUnavailableException(
                    "auction-service 응답 오류입니다. status=" + status);
        }
        return new UpstreamErrorException(
                "auction-service 호출에 실패했습니다. status=" + status);
    }

    private Long extractAuctionId(String url) {
        String path = url;
        int queryStart = path.indexOf('?');
        if (queryStart >= 0) {
            path = path.substring(0, queryStart);
        }
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash == path.length() - 1) {
            return null;
        }
        try {
            return Long.valueOf(path.substring(lastSlash + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
