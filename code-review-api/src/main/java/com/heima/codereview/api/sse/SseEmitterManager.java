package com.heima.codereview.api.sse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class SseEmitterManager {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterManager.class);

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> messageCounters = new ConcurrentHashMap<>();

    public void register(String sessionId, SseEmitter emitter) {
        emitters.put(sessionId, emitter);
        messageCounters.computeIfAbsent(sessionId, key -> new AtomicLong(0));
    }

    public void remove(String sessionId) {
        emitters.remove(sessionId);
        messageCounters.remove(sessionId);
    }

    public boolean isRegistered(String sessionId) {
        return emitters.containsKey(sessionId);
    }

    public void send(String sessionId, String eventName, Object data) {
        SseEmitter emitter = emitters.get(sessionId);
        if (emitter == null) {
            return;
        }
        try {
            long messageId = messageCounters.get(sessionId).incrementAndGet();
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(messageId))
                    .name(eventName)
                    .data(data, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            log.warn("SSE发送失败，移除会话: {}", sessionId);
            remove(sessionId);
        }
    }

    public Set<String> getAllSessions() {
        return emitters.keySet();
    }

    @Scheduled(fixedRate = 30000)
    public void sendHeartbeats() {
        long now = System.currentTimeMillis();
        getAllSessions().forEach(sessionId -> send(sessionId, "heartbeat", Map.of("timestamp", now)));
    }
}
