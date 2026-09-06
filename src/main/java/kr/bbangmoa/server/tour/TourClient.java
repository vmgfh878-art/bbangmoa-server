package kr.bbangmoa.server.tour;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 상류(관광공사)를 실제로 부르는 자리. 여기만 바깥 세상을 안다.
 * 컨트롤러는 이 클래스만 알고 apis.data.go.kr 이라는 주소는 모른다.
 */
@Component
public class TourClient {

    private static final Logger log = LoggerFactory.getLogger(TourClient.class);

    private final RestClient restClient;
    private final TourProperties props;

    public TourClient(RestClient tourRestClient, TourProperties props) {
        this.restClient = tourRestClient;
        this.props = props;
    }

    /** 상류 응답을 그대로 담아 올리는 그릇. 파싱하지 않는다. */
    public record Upstream(int status, MediaType contentType, byte[] body) {}

    public Upstream call(String operation, MultiValueMap<String, String> params) {
        URI uri = buildUri(operation, params);

        // 상류를 실제로 부를 때만 찍힌다. 캐시에 맞으면 이 줄이 안 나온다 —
        // 캐시가 도는지 확인하는 가장 확실한 증거다.
        //
        // uri 를 통째로 찍으면 안 된다. 거기 serviceKey 가 들어 있고,
        // 로그는 파일로 남고 나중에 통째로 어디로 보내질 수도 있다.
        // 키를 서버 뒤로 숨기려고 만든 프록시가 로그로 키를 흘리면 의미가 없다.
        log.info("상류 호출: {} (파라미터 {}개)", operation, params.size());

        try {
            return restClient.get()
                    .uri(uri)   // String 이 아니라 URI 를 넘긴다. String 을 넘기면 RestClient 가
                                // 한 번 더 인코딩해서 %3D 가 %253D 가 된다(이중 인코딩).
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        MediaType type = response.getHeaders().getContentType();
                        byte[] body = readAtMost(response.getBody(), props.maxResponseBytes());
                        return new Upstream(status, type, body);
                    });
        } catch (ResourceAccessException e) {
            // 연결 실패·타임아웃. 이걸 그냥 두면 스프링 기본 처리로 500 이 나간다.
            // 500 은 "우리 서버 코드가 터졌다"는 뜻이라 거짓말이 된다.
            // 504 Gateway Timeout 이 "내가 부른 상대가 제때 답을 안 했다"는 뜻이다.
            // 스택트레이스를 통째로 찍지 않는 이유: 상류가 느린 건 우리 버그가 아니고,
            // 트레이스가 수십 줄씩 쌓이면 로그에서 진짜 문제를 못 찾는다.
            log.warn("상류 타임아웃/연결 실패: {} — {}", operation, e.getMessage());
            throw new TourProxyException(504, "관광공사 응답이 제때 오지 않았다");
        } catch (RestClientException e) {
            log.warn("상류 호출 실패: {} — {}", operation, e.getMessage());
            throw new TourProxyException(502, "관광공사 호출에 실패했다");
        }
    }

    /**
     * 상류 URL 조립.
     *
     * 인코딩이 이 메서드의 전부다.
     *   스프링이 컨트롤러에 넘겨주는 쿼리 파라미터는 이미 URL 디코딩된 원본 문자열이다.
     *   serviceKey 도 우리가 가진 건 디코딩 키(+ / = 가 그대로 들어있다).
     *   즉 양쪽 다 "날것"이므로, 마지막에 encode() 로 딱 한 번만 인코딩하면 된다.
     *   여기서 이미 인코딩된 값을 섞으면 % 가 %25 로 다시 인코딩되면서
     *   관광공사가 인증 실패(SERVICE_KEY_IS_NOT_REGISTERED_ERROR)를 낸다.
     */
    private URI buildUri(String operation, MultiValueMap<String, String> params) {
        UriComponentsBuilder b = UriComponentsBuilder
                .fromUriString(props.baseUrl())
                .pathSegment(operation);

        params.forEach((name, values) -> {
            // 클라이언트가 보낸 serviceKey 는 버린다.
            // 키를 서버가 쥐는 게 이 프록시의 존재 이유인데,
            // 클라이언트가 보낸 값이 섞이면 파라미터가 두 개가 되어 상류가 거부한다.
            if (name.equalsIgnoreCase("serviceKey")) return;
            values.forEach(v -> b.queryParam(name, v));
        });

        b.queryParam("serviceKey", props.serviceKey());

        return b.build()                            // 아직 인코딩 전
                .encode(StandardCharsets.UTF_8)     // 여기서 딱 한 번
                .toUri();
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
            throw new TourProxyException(502,
                    "상류 응답이 상한(" + limit + " bytes)을 넘었다");
        }
        return data;
    }
}
