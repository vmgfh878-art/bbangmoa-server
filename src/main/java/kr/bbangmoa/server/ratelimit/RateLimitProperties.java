package kr.bbangmoa.server.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ratelimit")
public record RateLimitProperties(
        boolean enabled,
        /** IP 하나가 1분 동안 보낼 수 있는 요청 수. */
        int requestsPerMinute
) {}
