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
        String baseUrl,
        String serviceKey,
        Set<String> allowedOperations,
        Duration connectTimeout,
        Duration readTimeout,
        int maxResponseBytes,
        Cache cache
) {
    /**
     * 캐시 설정.
     *
     * TTL 을 하나로 안 두고 나눈 이유
     *   상세(detailCommon2 · detailIntro2)는 contentId 하나에 대한 고정 정보다.
     *   가게 설명이나 대표 이미지가 하루 안에 바뀔 일은 거의 없다.
     *   목록(areaBasedList2)은 신규 등록이 반영되므로 상세보다 자주 바뀐다.
     *   같은 TTL 을 쓰면 둘 중 하나는 손해다 — 상세는 필요 이상으로 자주 갱신되고,
     *   목록은 필요 이상으로 오래 낡는다.
     */
    public record Cache(
            boolean enabled,
            /** 목록 계열 TTL. */
            Duration listTtl,
            /** 상세 계열 TTL. */
            Duration detailTtl,
            /**
             * 예비 사본 수명. 신선한 캐시가 만료된 뒤에도 이만큼은 남겨둔다.
             * 상류가 죽었을 때 이 사본을 대신 내준다 — 낡은 데이터가 빈 화면보다 낫다.
             */
            Duration staleTtl,
            /** 여기 속하면 detailTtl, 아니면 listTtl 을 쓴다. */
            Set<String> detailOperations
    ) {
        public Duration ttlFor(String operation) {
            return detailOperations.contains(operation) ? detailTtl : listTtl;
        }
    }

    /** 키가 안 들어왔는지 판정. 빈 문자열도 없는 것으로 본다. */
    public boolean hasServiceKey() {
        return serviceKey != null && !serviceKey.isBlank();
    }
}
