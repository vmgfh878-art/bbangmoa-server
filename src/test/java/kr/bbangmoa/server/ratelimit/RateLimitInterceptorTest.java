package kr.bbangmoa.server.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 클라이언트 IP 판정.
 *
 * 이걸 테스트로 박아두는 이유
 *   운영에서 실제로 뚫렸던 자리다. X-Forwarded-For 를 직접 적어 보내면
 *   IP 당 레이트 리밋이 매번 초기화됐다(198.51.100.7 로 61회째 429 → .8 로 바꾸니 200).
 *   Nginx 가 X-Forwarded-For 는 덧붙이고 X-Real-IP 는 덮어쓴다는,
 *   같은 파일 안의 두 줄이 다르게 동작한다는 사실 위에 서 있는 판정이라
 *   나중에 누가 "둘 다 같은 거 아닌가" 하고 되돌리기 쉽다.
 */
class RateLimitInterceptorTest {

    @Test
    @DisplayName("X-Real-IP 가 있으면 그 값을 쓴다 — Nginx 가 덮어쓰는 헤더라 위조할 수 없다")
    void realIp를_우선한다() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr("127.0.0.1");          // Nginx 에서 온 것처럼
        r.addHeader("X-Real-IP", "203.0.113.5");

        assertEquals("203.0.113.5", RateLimitInterceptor.clientIp(r));
    }

    @Test
    @DisplayName("클라이언트가 X-Forwarded-For 를 위조해도 무시된다")
    void 위조된_XForwardedFor는_무시한다() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr("203.0.113.9");
        r.addHeader("X-Forwarded-For", "198.51.100.7");  // 공격자가 적어 보낸 값
        r.addHeader("X-Real-IP", "203.0.113.9");         // Nginx 가 덮어쓴 진짜 값

        assertEquals("203.0.113.9", RateLimitInterceptor.clientIp(r),
                "위조한 X-Forwarded-For 가 채택되면 IP 당 제한이 무의미해진다");
    }

    @Test
    @DisplayName("Nginx 없이 직접 부를 때(로컬)는 remoteAddr 로 돌아간다")
    void 헤더가_없으면_remoteAddr() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr("192.168.0.10");

        assertEquals("192.168.0.10", RateLimitInterceptor.clientIp(r));
    }

    @Test
    @DisplayName("빈 X-Real-IP 는 없는 것으로 친다 — 모든 요청이 빈 문자열 키로 뭉치는 걸 막는다")
    void 빈_헤더는_무시한다() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr("192.168.0.10");
        r.addHeader("X-Real-IP", "   ");

        assertEquals("192.168.0.10", RateLimitInterceptor.clientIp(r));
    }
}
