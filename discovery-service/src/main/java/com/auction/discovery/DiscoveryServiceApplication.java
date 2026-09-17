package com.auction.discovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * 로컬 개발용 Eureka 서버. 서비스들이 이름으로 서로를 찾게 한다.
 * GKE(D18)에서는 K8s Service DNS가 이 역할을 대신하므로 배포 대상이 아니다.
 */
@SpringBootApplication
@EnableEurekaServer
public class DiscoveryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DiscoveryServiceApplication.class, args);
    }
}
