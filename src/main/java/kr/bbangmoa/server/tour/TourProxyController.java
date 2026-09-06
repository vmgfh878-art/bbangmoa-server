package kr.bbangmoa.server.tour;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 관광공사 KorService2 통과형 프록시.
 *
 * 이 클래스가 하는 일은 셋뿐이다 — 형식 확인, 화이트리스트 판정, 위임.
 * 상류 주소도 모르고, 캐시가 있는지도 모르고, 응답 내용도 안 본다.
 * 그게 컨트롤러의 역할이다.
 */
@RestController
@RequestMapping("/api/tour")
public class TourProxyController {

    private final TourService service;
    private final TourProperties props;

    public TourProxyController(TourService service, TourProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/{operation}")
    public ResponseEntity<byte[]> proxy(
            @PathVariable String operation,
            @RequestParam MultiValueMap<String, String> params) {

        // 1) 화이트리스트. 여기 없으면 상류를 부르지도, 캐시를 보지도 않는다.
        //    404 를 주는 이유: 403("있는데 막혔다")은 어떤 경로가 존재하는지를
        //    알려주는 셈이라 탐색에 도움을 준다. 없는 것처럼 보이는 편이 낫다.
        if (!props.allowedOperations().contains(operation)) {
            throw new TourProxyException(404, "지원하지 않는 경로다: " + operation);
        }

        // 2) 키가 없으면 상류를 부를 수 없다.
        //    500(서버 버그)이 아니라 503(지금은 서비스 불가)이 맞다 —
        //    코드가 틀린 게 아니라 설정이 안 들어온 상태이기 때문이다.
        if (!props.hasServiceKey()) {
            throw new TourProxyException(503, "서버에 TOUR_API_KEY 가 설정되지 않았다");
        }

        TourService.Result r = service.fetch(operation, params);

        return ResponseEntity.ok()
                .contentType(r.contentType())
                // 캐시가 실제로 도는지 브라우저 Network 탭에서 바로 보이게 한다.
                // 표준 헤더는 아니지만 관례적으로 널리 쓰인다.
                .header("X-Cache", r.fromCache() ? "HIT" : "MISS")
                .body(r.body());
    }

    @ExceptionHandler(TourProxyException.class)
    ResponseEntity<Map<String, Object>> handle(TourProxyException e) {
        return ResponseEntity.status(e.status())
                .body(Map.of("error", HttpStatus.valueOf(e.status()).getReasonPhrase(),
                             "message", e.getMessage()));
    }
}
