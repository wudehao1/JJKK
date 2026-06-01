package com.wdh.jjkk_2.realtime;

import com.wdh.jjkk_2.service.AuthService;
import com.wdh.jjkk_2.dto.AuthSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * 基金 WebSocket 路由和握手配置。
 *
 * 路由实际会挂在 Spring Boot 的 context-path 下面，例如当前配置为 /api 时，
 * 小程序连接地址就是 /api/ws/funds。握手阶段会校验请求中的 token，并把认证后的
 * 用户会话放入 WebSocket attributes，后续订阅处理器即可知道连接属于哪个用户。
 */
@Configuration
@EnableWebSocket
public class RealtimeWebSocketConfig implements WebSocketConfigurer {
    private final FundRealtimeWebSocketHandler fundRealtimeWebSocketHandler;
    private final AuthService authService;

    public RealtimeWebSocketConfig(FundRealtimeWebSocketHandler fundRealtimeWebSocketHandler, AuthService authService) {
        this.fundRealtimeWebSocketHandler = fundRealtimeWebSocketHandler;
        this.authService = authService;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(fundRealtimeWebSocketHandler, "/ws/funds")
                .addInterceptors(authHandshakeInterceptor())
                .setAllowedOrigins("*");
    }

    private HandshakeInterceptor authHandshakeInterceptor() {
        return new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(
                    ServerHttpRequest request,
                    ServerHttpResponse response,
                    WebSocketHandler wsHandler,
                    Map<String, Object> attributes
            ) {
                try {
                    AuthSession session = authService.requireSession(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
                    attributes.put("userId", session.userId());
                    return true;
                } catch (Exception ignored) {
                    return false;
                }
            }

            @Override
            public void afterHandshake(
                    ServerHttpRequest request,
                    ServerHttpResponse response,
                    WebSocketHandler wsHandler,
                    Exception exception
            ) {
            }
        };
    }
}

