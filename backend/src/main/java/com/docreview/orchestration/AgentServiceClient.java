package com.docreview.orchestration;

import com.docreview.config.AppProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/** Java 到 Python LangGraph 服务的唯一出口。 */
@Component
public class AgentServiceClient {

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final WebClient webClient;
    private final Duration timeout;

    public AgentServiceClient(AppProperties props) {
        this.timeout = Duration.ofSeconds(props.getAgent().getTimeoutSeconds());
        HttpClient httpClient = HttpClient.create().responseTimeout(timeout);
        this.webClient = WebClient.builder()
                .baseUrl(props.getAgent().getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    public Flux<ServerSentEvent<String>> review(Object request) {
        return post("/internal/review", request);
    }

    public Flux<ServerSentEvent<String>> chat(Object request) {
        return post("/internal/chat", request);
    }

    private Flux<ServerSentEvent<String>> post(String uri, Object request) {
        return webClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(SSE_TYPE)
                .timeout(timeout)
                .onErrorMap(WebClientResponseException.class,
                        error -> new IllegalStateException("LangGraph 服务调用失败: "
                                + error.getStatusCode() + " " + error.getResponseBodyAsString(), error));
    }
}
