package kr.bbangmoa.server.config;

import kr.bbangmoa.server.ratelimit.RateLimitInterceptor;
import kr.bbangmoa.server.ratelimit.RateLimitProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class WebConfig implements WebMvcConfigurer {

    // application.yaml 의 app.cors.allowed-origins 에서 주입된다.
    // 그 값은 결국 CORS_ALLOWED_ORIGINS 환경변수 하나를 읽는다 —
    // 서버 .env 에서 그 값 하나만 바꾸면 여기와 Actuator CORS가 같이 바뀐다.
    private final String[] allowedOrigins;
    private final RateLimitInterceptor rateLimitInterceptor;

    public WebConfig(@Value("${app.cors.allowed-origins}") String allowedOrigins,
                     RateLimitInterceptor rateLimitInterceptor) {
        this.allowedOrigins = allowedOrigins.split("\s*,\s*");
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                // allowedOrigins 가 아니라 allowedOriginPatterns 를 쓰는 이유:
                // allowedOrigins 는 "정확히 같은 문자열" 또는 "*" 하나만 받는다.
                // 중간에 별이 들어간 https://*.vercel.app 는 어디에도 안 걸려서
                // 에러 없이 조용히 차단된다. Vercel 프리뷰 배포는 커밋마다 주소가
                // 바뀌므로 패턴 매칭이 필요하다.
                .allowedOriginPatterns(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                // 브라우저는 기본적으로 안전 목록 헤더(Content-Type, Cache-Control 등)만
                // JS 에 넘겨준다. 커스텀 헤더는 서버가 여기에 적어야 fetch 로 읽을 수 있다.
                // 서버는 X-Cache 를 보내고 있었지만 브라우저가 가려서 JS 에서는 null 이었다.
                // (DevTools Network 탭에는 원본이 보인다 — 그래서 더 헷갈린다)
                .exposedHeaders("X-Cache")
                // 프리플라이트(OPTIONS) 결과를 브라우저가 1시간 캐시한다.
                // 안 걸면 요청 하나마다 OPTIONS 가 한 번씩 더 날아간다.
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 레이트 리밋이 어디에 걸려 있는지가 이 한 줄에 다 보인다.
        // 프록시 경로에만 건다 — /actuator/health 까지 막으면
        // 배포 스크립트의 헬스 폴링이 429 를 맞고 배포가 실패한다.
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/tour/**");
    }
}
