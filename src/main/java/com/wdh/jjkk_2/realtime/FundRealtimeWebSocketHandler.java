package com.wdh.jjkk_2.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 基金实时通道的轻量 WebSocket 订阅处理器。
 *
 * 当前版本先记录每个连接订阅了哪些基金代码，并向前端返回订阅确认。这样前端可以保持
 * 长连接，后续如果要把分钟调度计算出的最新估算主动推送给用户，只需要在这里按订阅表
 * 广播消息，不必重新设计连接协议。
 */
@Component
public class FundRealtimeWebSocketHandler extends TextWebSocketHandler {
    private static final Pattern FUND_CODE_PATTERN = Pattern.compile("\\d{6}");

    private final ObjectMapper objectMapper;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> subscriptions = new ConcurrentHashMap<>();

    public FundRealtimeWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.put(session.getId(), session);
        subscriptions.put(session.getId(), ConcurrentHashMap.newKeySet());
        send(session, Map.of(
                "type", "CONNECTED",
                "sessionId", session.getId(),
                "serverTime", Instant.now().toString()
        ));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode root = objectMapper.readTree(message.getPayload());
        String type = root.path("type").asText("").toUpperCase();
        switch (type) {
            case "PING" -> send(session, Map.of("type", "PONG", "serverTime", Instant.now().toString()));
            case "SUBSCRIBE" -> subscribe(session, root.path("fundCodes"));
            case "UNSUBSCRIBE" -> unsubscribe(session, root.path("fundCodes"));
            default -> send(session, Map.of("type", "ERROR", "message", "\u4e0d\u652f\u6301\u7684\u6d88\u606f\u7c7b\u578b"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        subscriptions.remove(session.getId());
    }

    public int sessionCount() {
        return sessions.size();
    }

    public Map<String, Set<String>> snapshotSubscriptions() {
        return Map.copyOf(subscriptions);
    }

    private void subscribe(WebSocketSession session, JsonNode fundCodesNode) throws IOException {
        Set<String> codes = parseFundCodes(fundCodesNode);
        subscriptions.computeIfAbsent(session.getId(), key -> ConcurrentHashMap.newKeySet()).addAll(codes);
        send(session, Map.of("type", "SUBSCRIBED", "fundCodes", codes));
    }

    private void unsubscribe(WebSocketSession session, JsonNode fundCodesNode) throws IOException {
        Set<String> codes = parseFundCodes(fundCodesNode);
        subscriptions.computeIfAbsent(session.getId(), key -> ConcurrentHashMap.newKeySet()).removeAll(codes);
        send(session, Map.of("type", "UNSUBSCRIBED", "fundCodes", codes));
    }

    private Set<String> parseFundCodes(JsonNode fundCodesNode) {
        Set<String> codes = new LinkedHashSet<>();
        if (fundCodesNode != null && fundCodesNode.isArray()) {
            for (JsonNode node : fundCodesNode) {
                String code = node.asText("");
                if (FUND_CODE_PATTERN.matcher(code).matches()) {
                    codes.add(code);
                }
            }
        }
        return codes;
    }

    private void send(WebSocketSession session, Map<String, ?> payload) throws IOException {
        String json = objectMapper.writeValueAsString(payload);
        synchronized (session) {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(json));
            }
        }
    }
}

