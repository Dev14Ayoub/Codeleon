package com.codeleon.room.collab;

import com.codeleon.config.SecurityProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.Arrays;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class CollabWebSocketConfig implements WebSocketConfigurer {

    private final CollabWebSocketHandler handler;
    private final CollabHandshakeInterceptor handshakeInterceptor;
    private final SecurityProperties securityProperties;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] origins = Arrays.stream(securityProperties.corsAllowedOrigins().split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toArray(String[]::new);

        registry.addHandler(handler, "/ws/rooms/{roomId}")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins(origins.length == 0 ? new String[]{"*"} : origins);
    }
}
