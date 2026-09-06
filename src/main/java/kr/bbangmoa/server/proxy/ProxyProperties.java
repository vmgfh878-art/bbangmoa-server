package kr.bbangmoa.server.proxy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 모든 상류에 공통으로 적용되는 호출 정책.
 * 상류마다 다른 값은 UpstreamProperties 에, 공통은 여기에 둔다.
 */
@ConfigurationProperties(prefix = "app.proxy")
public record ProxyProperties(
        Duration connectTimeout,
        Duration readTimeout,
        int maxResponseBytes,
        /** 연결 실패 시 추가 시도 횟수. 0 이면 재시도 없음. */
        int retries
) {}
