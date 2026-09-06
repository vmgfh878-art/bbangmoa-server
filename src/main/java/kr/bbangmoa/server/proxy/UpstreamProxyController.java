package kr.bbangmoa.server.proxy;

import jakarta.servlet.http.HttpServletRequest;
import kr.bbangmoa.server.proxy.UpstreamProperties.Upstream;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 외부 API 통과형 프록시. 상류 넷이 이 클래스 하나를 공유한다.
 *
 *   GET  /api/tour/areaBasedList2?...            → apis.data.go.kr (키: 쿼리)
 *   GET  /api/kakao/v2/local/search/keyword.json → dapi.kakao.com  (키: 헤더)
 *   GET  /api/kakaonavi/v1/directions?...        → apis-navi.kakaomobility.com
 *   GET  /api/odsay/v1/api/searchPubTransPathT   → api.odsay.com   (키: 쿼리)
 *   POST /api/tmap/tmap/routes/pedestrian        → apis.openapi.sk.com (키: 헤더, 바디 JSON)
 *
 * 이 클래스가 하는 일은 넷뿐이다 — 상류 식별, 메서드 확인, 화이트리스트 판정, 위임.
 * 상류 주소도 모르고, 캐시가 있는지도 모르고, 응답 내용도 안 본다.
 */
@RestController
@RequestMapping("/api/{name}")
public class UpstreamProxyController {

    private final UpstreamService service;
    private final UpstreamProperties props;

    public UpstreamProxyController(UpstreamService service, UpstreamProperties props) {
        this.service = service;
        this.props = props;
    }

    @RequestMapping(value = "/**", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<byte[]> proxy(
            @PathVariable String name,
            @RequestParam MultiValueMap<String, String> params,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request) {

        // 1) 어떤 상류인가. 설정에 없는 이름이면 그런 경로는 없는 것이다.
        Upstream up = props.get(name);
        if (up == null) {
            throw new ProxyException(404, "알 수 없는 상류다: " + name);
        }

        String method = request.getMethod();
        if (!up.allowsMethod(method)) {
            // 405 는 "이 경로는 있는데 그 메서드는 안 된다"는 뜻이라 정확하다.
            throw new ProxyException(405, method + " 은(는) 허용되지 않는다");
        }

        String path = remainingPath(request, name);

        // 2) 화이트리스트. 여기 없으면 상류를 부르지도, 캐시를 보지도 않는다.
        //    404 를 주는 이유: 403("있는데 막혔다")은 어떤 경로가 존재하는지를
        //    알려주는 셈이라 탐색에 도움을 준다. 없는 것처럼 보이는 편이 낫다.
        if (!up.allows(path)) {
            throw new ProxyException(404, "지원하지 않는 경로다: " + path);
        }

        // 3) 키가 없으면 상류를 부를 수 없다.
        //    500(서버 버그)이 아니라 503(지금은 서비스 불가)이 맞다 —
        //    코드가 틀린 게 아니라 설정이 안 들어온 상태이기 때문이다.
        if (!up.hasKey()) {
            throw new ProxyException(503, name + " 의 API 키가 서버에 설정되지 않았다");
        }

        UpstreamService.Result r = service.fetch(name, up, method, path, params, body);

        return ResponseEntity.ok()
                .contentType(r.contentType())
                // 캐시가 실제로 도는지 브라우저 Network 탭에서 바로 보이게 한다.
                // STALE 이 보이면 상류가 죽어 있다는 뜻이다 — 장애 감지 수단.
                .header("X-Cache", r.cacheStatus())
                .body(r.body());
    }

    /**
     * /api/{name}/여기부터/끝까지 를 꺼낸다.
     *
     * @PathVariable 로 못 받는 이유: 경로 조각 수가 상류마다 다르다.
     *   관광공사는 한 조각(areaBasedList2), 카카오는 넷(v2/local/search/keyword.json).
     *   그래서 /** 로 받고 여기서 직접 잘라낸다.
     */
    private String remainingPath(HttpServletRequest request, String name) {
        String uri = request.getRequestURI();
        String prefix = "/api/" + name + "/";
        int i = uri.indexOf(prefix);
        String rest = i >= 0 ? uri.substring(i + prefix.length()) : "";
        // 경로에 한글이나 공백이 들어올 일은 없지만, 들어오면 화이트리스트 비교가
        // 인코딩된 문자열끼리 어긋난다. 비교 전에 한 번 풀어둔다.
        return URLDecoder.decode(rest, StandardCharsets.UTF_8);
    }

    @ExceptionHandler(ProxyException.class)
    ResponseEntity<Map<String, Object>> handle(ProxyException e) {
        return ResponseEntity.status(e.status())
                .body(Map.of("error", HttpStatus.valueOf(e.status()).getReasonPhrase(),
                             "message", e.getMessage()));
    }
}
