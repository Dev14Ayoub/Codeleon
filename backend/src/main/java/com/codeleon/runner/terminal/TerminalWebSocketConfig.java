package com.codeleon.runner.terminal;

import com.codeleon.config.SecurityProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.util.Arrays;

/**
 * Registers the interactive-terminal WebSocket endpoint at
 * {@code /ws/rooms/{roomId}/terminal}. With the {@code /api/v1} context path
 * the full URL is {@code /api/v1/ws/rooms/{id}/terminal}, which Caddy already
 * proxies via its {@code handle /api/*} block — no reverse-proxy change needed.
 *
 * <p>{@code @EnableWebSocket} lives on {@code CollabWebSocketConfig}; Spring
 * collects every {@code WebSocketConfigurer} bean, so this one is picked up too.
 */
@Configuration
@RequiredArgsConstructor
public class TerminalWebSocketConfig implements WebSocketConfigurer {

    private final TerminalWebSocketHandler handler;
    private final TerminalHandshakeInterceptor handshakeInterceptor;
    private final SecurityProperties securityProperties;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] origins = Arrays.stream(securityProperties.corsAllowedOrigins().split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toArray(String[]::new);

        registry.addHandler(handler, "/ws/rooms/{roomId}/terminal")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins(origins.length == 0 ? new String[]{"*"} : origins);
    }

    /**
     * The terminal init frame carries the room's files (capped ~750 KB), which
     * dwarfs the 8 KB default text buffer. Bump both buffers so large frames
     * (this plus collab sync) are not dropped.
     *
     * <p>Excluded from the {@code test} profile: it eagerly resolves the
     * {@code jakarta.websocket.server.ServerContainer} from the ServletContext,
     * which only exists with a real embedded servlet container — not in the
     * MockMvc ({@code webEnvironment=MOCK}) context the test suite uses.
     */
    @Bean
    @Profile("!test")
    public ServletServerContainerFactoryBean terminalServletServerContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(2 * 1024 * 1024);
        container.setMaxBinaryMessageBufferSize(2 * 1024 * 1024);
        return container;
    }
}
