package kr.bbangmoa.server.proxy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * app.upstreams.{이름}.* 을 통째로 받는다.
 *
 * 왜 상류마다 클래스를 만들지 않고 설정 하나로 묶었나
 *   네 상류의 차이는 로직이 아니라 데이터다 —
 *     키가 쿼리에 들어가나 헤더에 들어가나, GET 이냐 POST 냐, 어떤 경로를 여나.
 *   차이가 데이터면 코드를 복사할 게 아니라 설정으로 빼는 게 맞다.
 *   컨트롤러를 네 벌 두면 나중에 타임아웃 정책 하나 바꿀 때 네 군데를 고쳐야 하고,
 *   그중 하나를 빠뜨리는 게 이런 코드가 썩는 전형적인 방식이다.
 */
@ConfigurationProperties(prefix = "app")
public record UpstreamProperties(Map<String, Upstream> upstreams) {

    public record Upstream(
            /** 상류 주소. 요청 경로의 뒷부분이 여기 붙는다. */
            String baseUrl,

            /** QUERY = 쿼리 파라미터로, HEADER = 요청 헤더로 키를 붙인다. */
            AuthType authType,

            /** QUERY 면 파라미터 이름, HEADER 면 헤더 이름. */
            String authName,

            /** 헤더 값 앞에 붙일 접두사. 카카오는 "KakaoAK " 가 필요하고 TMAP 은 없다. */
            String authPrefix,

            /** 실제 키. 환경변수에서 온다. 로그·캐시키에 절대 넣지 않는다. */
            String authValue,

            /** 통과시킬 경로. 여기 없으면 상류를 부르지도 않고 404. */
            Set<String> allowedPaths,

            /** 허용 메서드. TMAP 만 POST 다. */
            Set<String> methods,

            /** 기본 캐시 수명. */
            Duration ttl,

            /** longTtlPaths 에 속한 경로에 적용할 더 긴 수명. */
            Duration longTtl,
            Set<String> longTtlPaths,

            /** 상류가 죽었을 때 내줄 예비 사본의 수명. */
            Duration staleTtl,

            /**
             * 요청에 항상 붙일 고정 헤더.
             *
             * ODSAY 가 Referer 로 등록 도메인을 검사한다 — 실측:
             *   Referer 없음                 → ApiKeyAuthFailed
             *   https://breadmoa.vercel.app/ → 정상
             *   http://localhost:5173/       → 정상
             * 서버가 부르면 Referer 가 없으니 그대로는 못 쓴다. 그래서 여기서 붙인다.
             *
             * 부수 효과: 이제 사용자가 어느 도메인에서 접속하든 상관없어진다.
             * 브라우저의 Referer 가 아니라 우리 서버가 정한 값이 나가기 때문이다.
             * breadmoa.com 을 ODSAY 에 새로 등록하지 않아도 된다.
             */
            Map<String, String> headers
    ) {
        public enum AuthType { QUERY, HEADER }

        public boolean hasKey() {
            return authValue != null && !authValue.isBlank();
        }

        public boolean allows(String path) {
            return allowedPaths.contains(path);
        }

        public boolean allowsMethod(String method) {
            return methods.contains(method);
        }

        public Duration ttlFor(String path) {
            return longTtlPaths != null && longTtlPaths.contains(path) ? longTtl : ttl;
        }
    }

    public Upstream get(String name) {
        return upstreams.get(name);
    }
}
