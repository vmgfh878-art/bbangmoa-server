package kr.bbangmoa.server.tour;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 컨트롤러와 상류 호출 사이의 층.
 *
 * 왜 컨트롤러에 캐시 코드를 안 넣었나
 *   컨트롤러가 "HTTP 로 들어온 걸 확인하는 일"과 "캐시에 있나 보는 일"을 같이 하면,
 *   나중에 이 로직을 다른 데서(스케줄러, 배치) 재사용할 수 없다.
 *   컨트롤러는 HTTP 를 알고, 서비스는 HTTP 를 모른다 — 이게 3계층의 경계다.
 *
 * 캐시 방식: 캐시 어사이드(cache-aside)
 *   1) 캐시를 본다  2) 없으면 상류를 부른다  3) 결과를 캐시에 넣는다
 *   애플리케이션이 캐시를 직접 다루는 방식이다. 반대는 캐시가 알아서
 *   뒤의 저장소를 읽어오는 read-through 인데, Redis 를 그렇게 쓰려면
 *   별도 구성이 필요하고 우리 상황엔 과하다.
 */
@Service
public class TourService {

    private static final Logger log = LoggerFactory.getLogger(TourService.class);

    private static final String FIELD_CONTENT_TYPE = "ct";
    private static final String FIELD_BODY = "body";

    private final TourClient client;
    private final StringRedisTemplate redis;
    private final TourProperties props;

    public TourService(TourClient client, StringRedisTemplate redis, TourProperties props) {
        this.client = client;
        this.redis = redis;
        this.props = props;
    }

    /** 캐시에서 나왔는지까지 알려준다. 컨트롤러가 X-Cache 헤더에 쓴다. */
    public record Result(int status, MediaType contentType, byte[] body, boolean fromCache) {}

    public Result fetch(String operation, MultiValueMap<String, String> params) {
        String key = cacheKey(operation, params);

        if (props.cache().enabled()) {
            Result hit = readCache(key);
            if (hit != null) return hit;
        }

        TourClient.Upstream up = client.call(operation, params);

        if (up.status() < 200 || up.status() >= 300) {
            // 실패는 캐시하지 않는다. 상류가 잠깐 이상했던 걸 TTL 동안 붙들고 있으면
            // 상류가 회복돼도 우리만 계속 실패를 돌려준다.
            throw new TourProxyException(502, "상류 응답 코드: " + up.status());
        }

        MediaType type = up.contentType() != null ? up.contentType() : MediaType.APPLICATION_JSON;
        if (props.cache().enabled()) {
            writeCache(key, operation, type, up.body());
        }
        return new Result(up.status(), type, up.body(), false);
    }

    /**
     * 캐시 키 = tour:{오퍼레이션}:{정렬된 쿼리스트링}
     *
     * 정렬하는 이유
     *   ?a=1&b=2 와 ?b=2&a=1 은 같은 요청인데 문자열로는 다르다.
     *   정렬을 안 하면 같은 데이터가 두 벌 캐시되고, 적중률이 그만큼 떨어진다.
     *
     * serviceKey 를 빼는 이유 둘
     *   1) 항상 같은 값이라 구분에 아무 쓸모가 없다.
     *   2) 더 중요한 건, 비밀값이 Redis 에 문자열로 남는다는 것이다.
     *      키를 서버 뒤로 숨기려고 만든 프록시가 키를 캐시에 흘리면 앞뒤가 안 맞는다.
     *      (애초에 params 에는 serviceKey 가 안 들어오지만, 나중에 누가
     *       파라미터를 늘렸을 때를 대비해 여기서도 한 번 더 거른다)
     */
    private String cacheKey(String operation, MultiValueMap<String, String> params) {
        List<String> parts = new ArrayList<>();
        params.forEach((name, values) -> {
            if (name.equalsIgnoreCase("serviceKey")) return;
            values.forEach(v -> parts.add(name + "=" + v));
        });
        parts.sort(String::compareTo);
        return "tour:" + operation + ":" + String.join("&", parts);
    }

    /**
     * 값을 Redis 해시로 저장한다 — 필드 두 개(ct, body).
     * 문자열 하나에 몰아넣고 나중에 잘라 쓰는 방법도 있지만,
     * 그러면 redis-cli 로 열어봤을 때 사람이 못 읽는다.
     * 캐시는 장애 때 제일 먼저 열어보는 곳이라 읽히는 게 중요하다.
     */
    private void writeCache(String key, String operation, MediaType type, byte[] body) {
        try {
            redis.opsForHash().putAll(key, Map.of(
                    FIELD_CONTENT_TYPE, type.toString(),
                    FIELD_BODY, new String(body, StandardCharsets.UTF_8)));
            redis.expire(key, props.cache().ttlFor(operation));
        } catch (Exception e) {
            // 캐시 쓰기 실패로 응답을 못 주면 본말전도다. 로그만 남기고 넘어간다.
            log.warn("캐시 쓰기 실패 — 응답은 그대로 준다: {}", e.toString());
        }
    }

    private Result readCache(String key) {
        try {
            Map<Object, Object> e = redis.opsForHash().entries(key);
            if (e.isEmpty()) return null;
            Object ct = e.get(FIELD_CONTENT_TYPE);
            Object body = e.get(FIELD_BODY);
            if (ct == null || body == null) return null;
            return new Result(200,
                    MediaType.parseMediaType(ct.toString()),
                    body.toString().getBytes(StandardCharsets.UTF_8),
                    true);
        } catch (Exception ex) {
            // Redis 가 죽어도 상류를 직접 부르면 서비스는 계속된다(fail-open).
            log.warn("캐시 읽기 실패 — 상류로 간다: {}", ex.toString());
            return null;
        }
    }
}
