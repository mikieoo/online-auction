package com.auction.auction.client;

/**
 * payment-service 응답의 status 값. payment-service의 enum을 공유하지 않고 문자열 계약만 따른다.
 * 알 수 없는 값이 오면 Feign 디코딩 예외(FeignException)로 올라와 해당 틱에서는 건너뛴다.
 */
public enum PaymentStatus {
    REQUESTED,
    COMPLETED,
    FAILED
}
