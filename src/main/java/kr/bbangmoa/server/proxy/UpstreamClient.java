package kr.bbangmoa.server.proxy;

import kr.bbangmoa.server.proxy.UpstreamProperties.Upstream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * 상류를 실제로 부르는 유일한 자리. 여기만 바깥 세상을 안다.
 * 컨트롤러는 이 클래스만 알고 apis.data.go.kr · dapi.kakao.com 같은 주소는 모른다.
 */
@Component
public class UpstreamClient {

    private static final Logger log = LoggerFactory.getLogger(UpstreamClient.class);

    private final RestClient restClient;
    private final ProxyProperties props;

    public UpstreamClient(RestClient proxyRestClient, ProxyProperties props) {
        this.restClient = proxyRestClient;
        this.props = props;
    }

    /** 상류 응답을 그대로 담아 올리는 그릇. 파싱하지 않는다. */
    public record Response(int status, MediaType contentType, byte[] body) {}

    /**
     * @param body POST 일 때의 요청 본문. GET 이면 null.
     */
    public Response call(String name, Upstream up, String method, String path,
                         MultiValueMap<String, String> params, byte[] body) {

        URI uri = buildUri(up, path, params);

        // 상류를 실제로 부를 때만 찍힌다. 캐시에 맞으면 이 줄이 안 나온다 —
        // 캐시가 도는지 확인하는 가장 확실한 증거다.
        //
        // uri 를 통째로 찍으면 안 된다. QUERY 인증인 상류는 거기 키가 들어 있고,
        // 로그는 파일로 남고 나중에 통째로 어디로 보내질 수도 있다.
        log.info("상류 호출: {} {} {}", name, method, path);

        // 실측(관광공사): 연결이 되면 20~70ms 안에 붙고, 안 되면 SYN 에 응답이 없다.
        // 그래서 짧게 끊고 정해진 횟수만큼 다시 두드린다.
        // 재시도가 안전한 이유: 연결이 아예 안 됐다 = 요청이 상류에 도달하지 않았다.
        // 응답을 받다가 끊긴 경우(읽기 타임아웃)는 재시도하지 않는다 — 중복 처리 위험.
        ResourceAccessException last = null;
        for (int attempt = 0; attempt <= props.retries(); attempt++) {
            try {
                return attempt(up, method, uri, body);
            } catch (ResourceAccessException e) {
                last = e;
                if (attempt < props.retries()) {
                    log.warn("상류 연결 실패, 재시도 {}/{}: {} — {}",
                            attempt + 1, props.retries(), name, e.getMessage());
                }
            } catch (RestClientException e) {
                log.warn("상류 호출 실패: {} — {}", name, e.getMessage());
                throw new ProxyException(502, "상류 호출에 실패했다");
            }
        }
        log.warn("상류 연결 최종 실패: {} — {}", name, last != null ? last.getMessage() : "");
        throw new ProxyException(504, "상류 응답이 제때 오지 않았다");
    }

    private Response attempt(Upstream up, String method, URI uri, byte[] body) {
        RestClient.RequestHeadersSpec<?> spec;

        if (HttpMethod.POST.name().equals(method)) {
            RestClient.RequestBodySpec post = restClient.post().uri(uri);
            // 상류가 JSON 을 기대한다. 통과형이라 바디를 해석하지 않고 그대로 넘긴다.
            post.contentType(MediaType.APPLICATION_JSON);
            spec = body != null ? post.body(body) : post;
        } else {
            spec = restClient.get().uri(uri);
        }

        // 상류가 요구하는 고정 헤더(ODSAY 의 Referer 등).
        if (up.headers() != null) {
            for (var e : up.headers().entrySet()) {
                spec = spec.header(e.getKey(), e.getValue());
            }
        }

        // HEADER 인증이면 여기서 키를 붙인다. 클라이언트가 보낸 헤더는 애초에 전달하지 않는다.
        if (up.authType() == Upstream.AuthType.HEADER) {
            String prefix = up.authPrefix() != null ? up.authPrefix() : "";
            spec = spec.header(up.authName(), prefix + up.authValue());
        }

        return spec.exchange((request, response) -> {
            int status = response.getStatusCode().value();
            MediaType type = response.getHeaders().getContentType();
            byte[] read = readAtMost(response.getBody(), props.maxResponseBytes());
            return new Response(status, type, read);
        });
    }

    /**
     * 상류 URL 조립.
     *
     * 인코딩이 이 메서드의 전부다.
     *   스프링이 컨트롤러에 넘겨주는 쿼리 파라미터는 이미 URL 디코딩된 원본 문자열이다.
     *   관광공사 서비스키도 우리가 가진 건 디코딩 키(+ / = 가 그대로 들어있다).
     *   즉 양쪽 다 "날것"이므로, 마지막에 encode() 로 딱 한 번만 인코딩하면 된다.
     *   여기서 이미 인코딩된 값을 섞으면 % 가 %25 로 다시 인코딩되면서
     *   상류가 인증 실패를 낸다.
     */
    private URI buildUri(Upstream up, String path, MultiValueMap<String, String> params) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(up.baseUrl());
        for (String seg : path.split("/")) {
            if (!seg.isEmpty()) b.pathSegment(seg);
        }

        String authName = up.authName();
        params.forEach((pname, values) -> {
            // 클라이언트가 보낸 인증 파라미터는 버린다.
            // 키를 서버가 쥐는 게 이 프록시의 존재 이유인데, 클라이언트가 보낸 값이
            // 섞이면 파라미터가 두 개가 되어 상류가 거부한다.
            if (up.authType() == Upstream.AuthType.QUERY && pname.equalsIgnoreCase(authName)) return;
            values.forEach(v -> b.queryParam(pname, v));
        });

        if (up.authType() == Upstream.AuthType.QUERY) {
            b.queryParam(authName, up.authValue());
        }

        return plusSafe(b.build()                       // 아직 인코딩 전
                         .encode(StandardCharsets.UTF_8));  // 여기서 딱 한 번
    }

    /**
     * 쿼리에 남은 '+' 를 %2B 로 바꾼다.
     *
     * 왜 필요한가 — 실제로 ODSAY 인증이 여기서 깨졌다.
     *   UriComponentsBuilder 는 '+' 를 쿼리의 합법 문자로 보고 그대로 둔다(RFC 3986 상 맞다).
     *   그런데 많은 서버가 쿼리의 '+' 를 폼 인코딩 관례에 따라 "공백"으로 해석한다.
     *   그래서 키에 '+' 가 하나라도 있으면 상대편이 다른 문자열로 읽고 인증에 실패한다.
     *   ODSAY 키에는 '+' 가 있고, 관광공사 키에는 없다 — 관광공사가 통과한 건 우연이다.
     *   (프론트는 encodeURIComponent 를 써서 %2B 로 보내고 있었으니 원래 맞았다)
     *
     * encode() 를 거친 뒤라 공백은 이미 %20 이 됐다. 즉 이 시점에 남아 있는 '+' 는
     * 전부 데이터에 들어 있던 진짜 플러스이므로, 통째로 바꿔도 안전하다.
     * 경로가 아니라 쿼리만 건드린다.
     */
    private URI plusSafe(org.springframework.web.util.UriComponents uc) {
        String query = uc.getQuery();
        if (query == null || query.indexOf('+') < 0) {
            return uc.toUri();
        }
        String full = uc.toUriString();
        int q = full.indexOf('?');
        return URI.create(full.substring(0, q + 1) + query.replace("+", "%2B"));
    }

    /**
     * 최대 limit 바이트까지만 읽는다.
     *
     * 왜 필요한가
     *   상류가 실수로(또는 공격으로) 거대한 응답을 주면 우리 서버 메모리가 그대로 찬다.
     *   RAM 1.8Gi 짜리 t4g.small 에서는 이 한 번으로 OOM 이 난다.
     *   Content-Length 헤더만 믿으면 안 되는 이유: 청크 전송이면 그 헤더가 아예 없다.
     *   그래서 헤더가 아니라 실제로 읽은 바이트 수로 판정한다.
     */
    private byte[] readAtMost(InputStream in, int limit) throws IOException {
        // limit + 1 바이트를 시도한다. limit + 1 이 읽히면 "한계를 넘었다"가 확정된다.
        byte[] data = in.readNBytes(limit + 1);
        if (data.length > limit) {
            throw new ProxyException(502, "상류 응답이 상한(" + limit + " bytes)을 넘었다");
        }
        return data;
    }
}
