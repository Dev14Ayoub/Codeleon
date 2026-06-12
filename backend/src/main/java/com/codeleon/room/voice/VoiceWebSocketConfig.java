package com.codeleon.room.voice;

import com.codeleon.config.SecurityProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.Arrays;

/**
 * Registers the voice-call signaling endpoint at
 * {@code /ws/rooms/{roomId}/voice} (served under the {@code /api/v1} context
 * path → routed by Caddy's {@code /api/*} block). {@code @EnableWebSocket} lives
 * on {@code CollabWebSocketConfig}; this configurer is collected automatically.
 */
@Configuration
@RequiredArgsConstructor
public class VoiceWebSocketConfig implements WebSocketConfigurer {

    private final VoiceSignalingHandler handler;
    private final VoiceHandshakeInterceptor handshakeInterceptor;
    private final SecurityProperties securityProperties;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] origins = Arrays.stream(securityProperties.corsAllowedOrigins().split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toArray(String[]::new);

        registry.addHandler(handler, "/ws/rooms/{roomId}/voice")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins(origins.length == 0 ? new String[]{"*"} : origins);
    }
}
