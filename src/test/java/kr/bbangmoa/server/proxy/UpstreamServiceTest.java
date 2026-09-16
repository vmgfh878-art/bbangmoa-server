package kr.bbangmoa.server.proxy;

import kr.bbangmoa.server.proxy.UpstreamProperties.Upstream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UpstreamService 는 캐시-어사이드·쿼터·stale 폴백이 한데 얽힌 자리라 분기 하나만
 * 잘못 건드려도 조용히 상류를 더 부르거나(쿼터 낭비) 캐시가 안 도는 채로 넘어간다.
 *
 * @SpringBootTest 로 전체 컨텍스트(JPA·Flyway 포함)를 띄우지 않고, Redis 연결만
 * 직접 만든다 — 필요한 건 진짜 Redis뿐이고, 나머지 부팅 비용은 이 테스트와 무관하다.
 * CI의 redis 서비스 컨테이너(localhost:6379)에 application.yaml 기본값과 동일하게 붙는다.
 *
 * 상류는 FakeUpstreamClient(하단)로 대신한다 — UpstreamClient가 인터페이스가 아니라
 * 구체 클래스라, call()만 오버라이드해서 실제 소켓 없이 응답/실패를 미리 정해둔다.
 */
class UpstreamServiceTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void setUpRedis() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void tearDownRedis() {
        connectionFactory.destroy();
    }

    /** 테스트마다 다른 상류 이름을 써서 Redis 키가 서로 안 겹치게 한다 — 정리(cleanup) 코드가 필요 없다. */
    private static String freshName(String label) {
        return "svc-" + label + "-" + UUID.randomUUID();
    }

    private Upstream upstream(Duration ttl, Duration staleTtl, Upstream.Quota quota) {
        return new Upstream(null, null, null, null, null, null, ttl, staleTtl, null, quota);
    }

    private UpstreamService service(FakeUpstreamClient fake) {
        return new UpstreamService(fake, redis, new UpstreamQuota(redis));
    }

    @Test
    @DisplayName("첫 호출은 MISS(상류를 부른다), 같은 요청 재호출은 HIT(상류를 다시 안 부른다)")
    void 캐시_MISS_후_HIT() {
        FakeUpstreamClient fake = new FakeUpstreamClient();
        UpstreamService service = service(fake);
        Upstream up = upstream(Duration.ofMinutes(10), Duration.ZERO, null);

        byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        fake.willReturn(new UpstreamClient.Response(200, MediaType.APPLICATION_JSON, body));

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("q", "x");
        String name = freshName("hit");

        UpstreamService.Result first = service.fetch(name, up, "GET", "path", params, null);
        assertEquals("MISS", first.cacheStatus());
        assertEquals(1, fake.callCount());

        UpstreamService.Result second = service.fetch(name, up, "GET", "path", params, null);
        assertEquals("HIT", second.cacheStatus());
        assertEquals(1, fake.callCount(), "캐시가 있는데 상류를 또 불렀다");
        assertArrayEquals(body, second.body());
    }

    @Test
    @DisplayName("인증 파라미터(serviceKey 등) 값이 달라도 같은 캐시 엔트리로 취급된다 — 비밀값이 캐시 키에 안 들어간다")
    void 인증파라미터는_캐시키에서_제외된다() {
        FakeUpstreamClient fake = new FakeUpstreamClient();
        UpstreamService service = service(fake);
        Upstream up = upstream(Duration.ofMinutes(10), Duration.ZERO, null);
        String name = freshName("authkey");

        fake.willReturn(new UpstreamClient.Response(200, MediaType.APPLICATION_JSON,
                "{}".getBytes(StandardCharsets.UTF_8)));

        MultiValueMap<String, String> p1 = new LinkedMultiValueMap<>();
        p1.add("serviceKey", "key-A");
        p1.add("q", "x");
        service.fetch(name, up, "GET", "path", p1, null);

        MultiValueMap<String, String> p2 = new LinkedMultiValueMap<>();
        p2.add("serviceKey", "key-B");   // 값만 다름, 나머지는 동일
        p2.add("q", "x");
        UpstreamService.Result second = service.fetch(name, up, "GET", "path", p2, null);

        assertEquals("HIT", second.cacheStatus(), "serviceKey 값만 달라도 다른 캐시 엔트리로 취급됐다");
        assertEquals(1, fake.callCount());
    }

    @Test
    @DisplayName("ttl=0, staleTtl=0 인 경로는 캐시를 아예 안 쓴다 — 매번 상류로 간다(TMAP 방식)")
    void ttl이_0이면_캐시를_안_쓴다() {
        FakeUpstreamClient fake = new FakeUpstreamClient();
        UpstreamService service = service(fake);
        Upstream up = upstream(Duration.ZERO, Duration.ZERO, null);
        String name = freshName("off");

        fake.willReturn(new UpstreamClient.Response(200, MediaType.APPLICATION_JSON, "{}".getBytes(StandardCharsets.UTF_8)));
        fake.willReturn(new UpstreamClient.Response(200, MediaType.APPLICATION_JSON, "{}".getBytes(StandardCharsets.UTF_8)));

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        UpstreamService.Result first = service.fetch(name, up, "POST", "path", params, null);
        UpstreamService.Result second = service.fetch(name, up, "POST", "path", params, null);

        assertEquals("OFF", first.cacheStatus());
        assertEquals("OFF", second.cacheStatus());
        assertEquals(2, fake.callCount(), "캐시가 꺼져 있는데 상류를 한 번만 불렀다");
    }

    @Test
    @DisplayName("하루 한도를 다 쓰면 상류를 부르지 않고 예비 사본(STALE)을 준다")
    void 쿼터소진시_stale로_응답한다() throws InterruptedException {
        FakeUpstreamClient fake = new FakeUpstreamClient();
        UpstreamService service = service(fake);
        // ttl을 짧게 둬서 실제로 만료시킨다 — 캐시 키 포맷을 몰라도 "신선 캐시 없음"을 재현할 수 있다.
        Upstream up = upstream(Duration.ofMillis(600), Duration.ofDays(1), new Upstream.Quota(null, 1));
        String name = freshName("quota-stale");

        byte[] staleBody = "{\"stale\":true}".getBytes(StandardCharsets.UTF_8);
        fake.willReturn(new UpstreamClient.Response(200, MediaType.APPLICATION_JSON, staleBody));

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        UpstreamService.Result first = service.fetch(name, up, "GET", "path", params, null);
        assertEquals("MISS", first.cacheStatus());
        assertEquals(1, fake.callCount());

        Thread.sleep(800); // 신선 캐시(600ms) 만료 대기. stale(1일)은 아직 살아있다.

        UpstreamService.Result second = service.fetch(name, up, "GET", "path", params, null);
        assertEquals("STALE", second.cacheStatus());
        assertEquals(1, fake.callCount(), "쿼터가 소진됐는데 상류를 또 불렀다 — stale 로 응답했어야 한다");
        assertArrayEquals(staleBody, second.body());
    }

    @Test
    @DisplayName("하루 한도를 다 썼고 예비 사본도 없으면 429")
    void 쿼터소진_stale없으면_429() throws InterruptedException {
        FakeUpstreamClient fake = new FakeUpstreamClient();
        UpstreamService service = service(fake);
        Upstream up = upstream(Duration.ofMillis(600), Duration.ZERO, new Upstream.Quota(null, 1));
        String name = freshName("quota-429");

        fake.willReturn(new UpstreamClient.Response(200, MediaType.APPLICATION_JSON, "{}".getBytes(StandardCharsets.UTF_8)));

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        service.fetch(name, up, "GET", "path", params, null);   // 1/1 쿼터 소진
        Thread.sleep(800);

        ProxyException e = assertThrows(ProxyException.class,
                () -> service.fetch(name, up, "GET", "path", params, null));
        assertEquals(429, e.status());
        assertEquals(1, fake.callCount());
    }

    @Test
    @DisplayName("상류 호출이 실패해도 예비 사본이 있으면 그걸로 응답한다")
    void 상류실패시_stale로_폴백한다() throws InterruptedException {
        FakeUpstreamClient fake = new FakeUpstreamClient();
        UpstreamService service = service(fake);
        Upstream up = upstream(Duration.ofMillis(600), Duration.ofDays(1), null);
        String name = freshName("fail-stale");

        byte[] staleBody = "{\"stale\":true}".getBytes(StandardCharsets.UTF_8);
        fake.willReturn(new UpstreamClient.Response(200, MediaType.APPLICATION_JSON, staleBody));

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        service.fetch(name, up, "GET", "path", params, null);   // 정상 1회 — 캐시·stale 둘 다 채운다.

        Thread.sleep(800); // 신선 캐시 만료 대기

        fake.willThrow(new ProxyException(502, "상류가 죽었다"));
        UpstreamService.Result r = service.fetch(name, up, "GET", "path", params, null);

        assertEquals("STALE", r.cacheStatus());
        assertArrayEquals(staleBody, r.body());
    }

    @Test
    @DisplayName("상류 호출이 실패하고 예비 사본도 없으면 예외가 그대로 전파된다")
    void 상류실패시_stale없으면_예외가_전파된다() {
        FakeUpstreamClient fake = new FakeUpstreamClient();
        UpstreamService service = service(fake);
        Upstream up = upstream(Duration.ofMinutes(10), Duration.ZERO, null);   // staleTtl 없음
        String name = freshName("fail-nostale");

        fake.willThrow(new ProxyException(502, "상류가 죽었다"));

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        ProxyException e = assertThrows(ProxyException.class,
                () -> service.fetch(name, up, "GET", "path", params, null));
        assertEquals(502, e.status());
    }

    @Test
    @DisplayName("상류가 2xx 가 아니면 502로 바꾸고, 그 실패는 캐시하지 않는다")
    void 비2xx_응답은_502이고_캐시하지_않는다() {
        FakeUpstreamClient fake = new FakeUpstreamClient();
        UpstreamService service = service(fake);
        Upstream up = upstream(Duration.ofMinutes(10), Duration.ZERO, null);
        String name = freshName("non2xx");

        fake.willReturn(new UpstreamClient.Response(500, MediaType.APPLICATION_JSON, "{}".getBytes(StandardCharsets.UTF_8)));
        fake.willReturn(new UpstreamClient.Response(200, MediaType.APPLICATION_JSON, "{\"ok\":true}".getBytes(StandardCharsets.UTF_8)));

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        ProxyException e = assertThrows(ProxyException.class,
                () -> service.fetch(name, up, "GET", "path", params, null));
        assertEquals(502, e.status());

        // 실패는 캐시되지 않았어야 한다 — 다음 호출은 다시 상류로 가서(준비해둔 두 번째 응답) 성공해야 한다.
        UpstreamService.Result r = service.fetch(name, up, "GET", "path", params, null);
        assertEquals("MISS", r.cacheStatus());
        assertEquals(2, fake.callCount());
    }

    /** call()만 오버라이드한다 — 부모 필드(restClient·props)는 이 경로에서 안 쓰이므로 null로 둬도 안전하다. */
    private static class FakeUpstreamClient extends UpstreamClient {

        private final Deque<Object> queue = new ArrayDeque<>();
        private final AtomicInteger calls = new AtomicInteger();

        FakeUpstreamClient() {
            super(null, null);
        }

        void willReturn(Response response) {
            queue.add(response);
        }

        void willThrow(RuntimeException e) {
            queue.add(e);
        }

        int callCount() {
            return calls.get();
        }

        @Override
        public Response call(String name, Upstream up, String method, String path,
                             MultiValueMap<String, String> params, byte[] body) {
            calls.incrementAndGet();
            Object next = queue.poll();
            if (next == null) {
                throw new IllegalStateException("FakeUpstreamClient: 준비된 응답/실패가 없다");
            }
            if (next instanceof RuntimeException re) {
                throw re;
            }
            return (Response) next;
        }
    }
}
