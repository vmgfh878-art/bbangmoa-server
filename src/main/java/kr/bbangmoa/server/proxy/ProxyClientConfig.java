package kr.bbangmoa.server.proxy;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
@EnableConfigurationProperties({ProxyProperties.class, UpstreamProperties.class})
public class ProxyClientConfig {

    /**
     * 외부 API 호출 전용 RestClient.
     *
     * 타임아웃이 왜 둘인가
     *   connectTimeout : 상대와 TCP 연결이 맺어지기까지. 상대가 SYN 을 버리면 여기서 걸린다.
     *   readTimeout    : 연결은 됐는데 응답 바이트가 안 오는 시간.
     *   둘 다 없으면 무한 대기다. 그러면 이쪽 스레드가 계속 붙잡혀서, 상류 하나가
     *   느려진 것만으로 우리 서버 전체가 응답을 못 하게 된다.
     *
     * RestClient.Builder 를 주입받지 않고 정적 팩토리를 쓰는 이유:
     *   지금 의존성 조합(starter-webmvc)에는 그 빌더를 만들어주는 자동 설정이 없다.
     *   어차피 요청 팩토리를 여기서 직접 지정하므로 자동 설정이 해줄 일이 없다.
     */
    @Bean
    RestClient proxyRestClient(ProxyProperties props) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(props.readTimeout());

        return RestClient.builder().requestFactory(factory).build();
    }
}
