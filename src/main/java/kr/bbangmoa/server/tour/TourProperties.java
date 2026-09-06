package kr.bbangmoa.server.tour;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Set;

/**
 * application.yaml 의 app.tour.* 를 통째로 받는다.
 *
 * 왜 @Value 를 흩뿌리지 않고 record 하나로 묶었나
 *   - 설정이 어디서 오는지가 파일 하나에 모인다. @Value 는 클래스마다 흩어져서
 *     "이 서버가 읽는 설정 전체"를 파악하려면 전수조사를 해야 한다.
 *   - record 라 불변이다. 런타임에 누가 바꿀 수 없다.
 */
@ConfigurationProperties(prefix = "app.tour")
public record TourProperties(
        /** 상류 주소. 뒤에 /areaBasedList2 같은 게 붙는다. */
        String baseUrl,

        /** 관광공사 서비스키(디코딩 키). 환경변수 TOUR_API_KEY 에서 온다. */
        String serviceKey,

        /**
         * 통과시킬 오퍼레이션 목록. 여기 없는 이름은 404.
         * List 가 아니라 Set 인 이유: contains 가 O(1) 이고, 중복 설정이 의미 없다.
         */
        Set<String> allowedOperations,

        /** 상류와 TCP 연결을 맺기까지 기다리는 시간. */
        Duration connectTimeout,

        /** 연결된 뒤 응답 바이트가 안 올 때 기다리는 시간. 연결 타임아웃과 별개다. */
        Duration readTimeout,

        /** 상류 응답 본문 상한. 넘으면 끊는다. */
        int maxResponseBytes
) {
    /** 키가 안 들어왔는지 판정. 빈 문자열도 없는 것으로 본다. */
    public boolean hasServiceKey() {
        return serviceKey != null && !serviceKey.isBlank();
    }
}
