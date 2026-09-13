package kr.bbangmoa.server.proxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import kr.bbangmoa.server.proxy.UpstreamProperties.Upstream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UpstreamClient 는 상류를 실제로 부르는 유일한 자리라 여기서 나는 버그는
 * 곧바로 인증 실패나 OOM 같은 실사용 장애로 이어진다(ODSAY '+' 인코딩 사고가 실제 사례다).
 *
 * 목(mock) 프레임워크 대신 JDK 내장 HttpServer 로 진짜 소켓 서버를 띄워 검증한다 —
 * URI 조립·인코딩은 문자열 조작이 아니라 "실제로 상대가 무엇을 받았는가"로만 확인할 수 있다.
 */
class UpstreamClientTest {

    private HttpServer server;
    private ExecutorService executor;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    /** 요청마다 독립된 스레드로 처리한다 — 재시도 두 번째 요청이 첫 번째 핸들러 뒤에 밀려 줄서지 않게. */
    private int start(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", handler);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
        return server.getAddress().getPort();
    }

    private UpstreamClient client(Duration connectTimeout, Duration readTimeout, int maxBytes, int retries) {
        ProxyProperties props = new ProxyProperties(connectTimeout, readTimeout, maxBytes, retries);
        RestClient restClient = new ProxyClientConfig().proxyRestClient(props);
        return new UpstreamClient(restClient, props);
    }

    private Upstream upstream(String baseUrl, Upstream.AuthType authType, String authName,
                              String authPrefix, String authValue, Map<String, String> headers) {
        return new Upstream(baseUrl, authType, authName, authPrefix, authValue,
                null, null, null, headers, null);
    }

    private void respond(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    @Test
    @DisplayName("쿼리의 '+' 는 공백이 아니라 %2B 로 인코딩되어 상류에 도달한다 — ODSAY 인증 실패 재발 방지")
    void plus_기호가_퍼센트인코딩된다() throws IOException {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        int port = start(ex -> {
            rawQuery.set(ex.getRequestURI().getRawQuery());
            respond(ex, 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        });

        UpstreamClient c = client(Duration.ofSeconds(2), Duration.ofSeconds(2), 1_000_000, 0);
        Upstream up = upstream("http://localhost:" + port, Upstream.AuthType.QUERY, "apiKey", null, "secret", null);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("q", "a+b");
        c.call("test", up, "GET", "search", params, null);

        assertNotNull(rawQuery.get());
        assertTrue(rawQuery.get().contains("q=a%2Bb"),
                "raw query에 %2B 인코딩이 없다: " + rawQuery.get());
        assertFalse(rawQuery.get().contains("a+b"),
                "'+' 가 인코딩되지 않은 채로 남아 있다 — 상류에서 공백으로 오인된다: " + rawQuery.get());
    }

    @Test
    @DisplayName("QUERY 인증: 클라이언트가 보낸 인증 파라미터는 버리고 서버가 쥔 값만 붙는다")
    void 쿼리인증은_서버_값만_실린다() throws IOException {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        int port = start(ex -> {
            rawQuery.set(ex.getRequestURI().getRawQuery());
            respond(ex, 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        });

        UpstreamClient c = client(Duration.ofSeconds(2), Duration.ofSeconds(2), 1_000_000, 0);
        Upstream up = upstream("http://localhost:" + port, Upstream.AuthType.QUERY, "apiKey", null, "server-secret", null);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("apiKey", "client-forged");
        params.add("q", "x");
        c.call("test", up, "GET", "search", params, null);

        String q = rawQuery.get();
        assertTrue(q.contains("apiKey=server-secret"), "서버 키가 없다: " + q);
        assertFalse(q.contains("client-forged"), "클라이언트가 보낸 위조 키가 그대로 섞여 나갔다: " + q);
        long occurrences = q.split("apiKey=", -1).length - 1;
        assertEquals(1, occurrences, "apiKey 가 중복으로 실렸다(클라이언트 값 + 서버 값): " + q);
    }

    @Test
    @DisplayName("HEADER 인증: 접두사 + 값이 그대로 헤더에 실린다")
    void 헤더인증은_접두사가_붙는다() throws IOException {
        AtomicReference<String> auth = new AtomicReference<>();
        int port = start(ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            respond(ex, 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        });

        UpstreamClient c = client(Duration.ofSeconds(2), Duration.ofSeconds(2), 1_000_000, 0);
        Upstream up = upstream("http://localhost:" + port, Upstream.AuthType.HEADER, "Authorization", "KakaoAK ", "abc123", null);

        c.call("test", up, "GET", "path", new LinkedMultiValueMap<>(), null);

        assertEquals("KakaoAK abc123", auth.get());
    }

    @Test
    @DisplayName("설정에 고정 헤더가 있으면(Referer 등) 상류 요청에 그대로 실린다")
    void 고정_헤더가_실린다() throws IOException {
        AtomicReference<String> referer = new AtomicReference<>();
        int port = start(ex -> {
            referer.set(ex.getRequestHeaders().getFirst("Referer"));
            respond(ex, 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        });

        UpstreamClient c = client(Duration.ofSeconds(2), Duration.ofSeconds(2), 1_000_000, 0);
        Upstream up = upstream("http://localhost:" + port, Upstream.AuthType.QUERY, "apiKey", null, "k",
                Map.of("Referer", "https://breadmoa.vercel.app/"));

        c.call("test", up, "GET", "path", new LinkedMultiValueMap<>(), null);

        assertEquals("https://breadmoa.vercel.app/", referer.get());
    }

    @Test
    @DisplayName("상류 응답(상태·타입·본문)을 그대로 돌려준다 — 4xx/5xx 도 여기서는 예외로 바꾸지 않는다")
    void 상류_응답을_그대로_전달한다() throws IOException {
        int port = start(ex -> respond(ex, 404, "application/json",
                "{\"e\":1}".getBytes(StandardCharsets.UTF_8)));

        UpstreamClient c = client(Duration.ofSeconds(2), Duration.ofSeconds(2), 1_000_000, 0);
        Upstream up = upstream("http://localhost:" + port, Upstream.AuthType.QUERY, "apiKey", null, "k", null);

        UpstreamClient.Response r = c.call("test", up, "GET", "path", new LinkedMultiValueMap<>(), null);

        assertEquals(404, r.status(), "상류의 4xx/5xx 는 UpstreamClient 단계에서 예외가 아니라 그대로 반환돼야 한다"
                + " — 그 판단은 UpstreamService 의 몫이다");
        assertEquals("{\"e\":1}", new String(r.body(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("응답이 상한을 넘으면 재시도 없이 즉시 502 — 상류가 거대한 응답을 줘도 메모리를 다 먹지 않는다")
    void 응답_상한_초과시_즉시_502() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        byte[] big = new byte[200];
        int port = start(ex -> {
            hits.incrementAndGet();
            respond(ex, 200, "application/json", big);
        });

        UpstreamClient c = client(Duration.ofSeconds(2), Duration.ofSeconds(2), 100, 1);
        Upstream up = upstream("http://localhost:" + port, Upstream.AuthType.QUERY, "apiKey", null, "k", null);

        ProxyException e = assertThrows(ProxyException.class,
                () -> c.call("test", up, "GET", "path", new LinkedMultiValueMap<>(), null));

        assertEquals(502, e.status());
        assertEquals(1, hits.get(), "응답 상한 초과는 연결 실패가 아니므로 재시도되면 안 된다");
    }

    @Test
    @DisplayName("연결 자체가 안 되면(포트에 아무도 없음) 재시도 후 504")
    void 연결_실패시_504() throws IOException {
        int deadPort;
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort = s.getLocalPort();
        }
        // 소켓을 닫았으니 이 포트는 이제 아무도 듣고 있지 않다 — 연결 거부가 재현된다.

        UpstreamClient c = client(Duration.ofMillis(300), Duration.ofSeconds(2), 1_000_000, 1);
        Upstream up = upstream("http://localhost:" + deadPort, Upstream.AuthType.QUERY, "apiKey", null, "k", null);

        ProxyException e = assertThrows(ProxyException.class,
                () -> c.call("test", up, "GET", "path", new LinkedMultiValueMap<>(), null));

        assertEquals(504, e.status());
    }

    @Test
    @Timeout(10)
    @DisplayName("읽기 타임아웃(연결은 됐고 응답만 늦음)은 재시도하지 않는다 — 연결 실패와 구분해야 한다")
    void 읽기_타임아웃은_재시도하지_않는다() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        int port = start(ex -> {
            hits.incrementAndGet();
            try {
                // readTimeout(500ms)보다 오래 응답을 미룬다 — 연결은 됐지만 응답이 안 오는 상황을 재현한다.
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try {
                respond(ex, 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // 클라이언트가 이미 타임아웃으로 끊었을 수 있다 — 이 테스트의 관심사는 응답 성공 여부가 아니다.
            }
        });

        UpstreamClient c = client(Duration.ofMillis(300), Duration.ofMillis(500), 1_000_000, 1);
        Upstream up = upstream("http://localhost:" + port, Upstream.AuthType.QUERY, "apiKey", null, "k", null);

        try {
            assertThrows(ProxyException.class,
                    () -> c.call("test", up, "GET", "path", new LinkedMultiValueMap<>(), null));
        } finally {
            release.countDown();
        }

        // UpstreamClient.isConnectFailure() 가 ConnectException/HttpConnectTimeoutException 만
        // 재시도 대상으로 보고, 그 외 HttpTimeoutException(=읽기 타임아웃)은 즉시 504 로 끊어야 한다.
        // hits 가 2로 나오면 그 구분이 다시 깨진 것 — 상류에 같은 요청이 중복으로 나간다.
        assertEquals(1, hits.get(),
                "읽기 타임아웃이 재시도됐다 — 연결 실패와 구분되지 않고 있다");
    }
}
