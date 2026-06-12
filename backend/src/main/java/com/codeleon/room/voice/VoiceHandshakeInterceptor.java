package com.codeleon.room.voice;

import com.codeleon.config.JwtService;
import com.codeleon.room.RoomFileService;
import com.codeleon.user.User;
import com.codeleon.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authenticates the voice-call signaling WebSocket. Mirrors the collab
 * interceptor: JWT in the {@code token} query param, room read access required
 * (any member may join a call).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USER = "voice.user";
    public static final String ATTR_ROOM_ID = "voice.roomId";

    private static final Pattern PATH_PATTERN =
            Pattern.compile("/ws/rooms/([0-9a-fA-F-]{36})/voice/?$");

    private final JwtService jwtService;
    private final UserService userService;
    private final RoomFileService roomFileService;

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        Matcher matcher = PATH_PATTERN.matcher(request.getURI().getPath());
        if (!matcher.find()) {
            reject(response, HttpStatus.BAD_REQUEST, "Invalid websocket path");
            return false;
        }
        UUID roomId = UUID.fromString(matcher.group(1));

        String token = extractToken(request);
        if (token == null || token.isBlank()) {
            reject(response, HttpStatus.UNAUTHORIZED, "Missing token");
            return false;
        }

        try {
            String email = jwtService.extractEmail(token);
            User user = (User) userService.loadUserByUsername(email);
            if (!jwtService.isValid(token, user)) {
                reject(response, HttpStatus.UNAUTHORIZED, "Invalid token");
                return false;
            }
            if (!roomFileService.canRead(roomId, user)) {
                reject(response, HttpStatus.FORBIDDEN, "Not a member of this room");
                return false;
            }
            attributes.put(ATTR_USER, user);
            attributes.put(ATTR_ROOM_ID, roomId);
            return true;
        } catch (RuntimeException ex) {
            log.debug("Voice handshake rejected", ex);
            reject(response, HttpStatus.UNAUTHORIZED, "Authentication failed");
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
        // No-op
    }

    private String extractToken(ServerHttpRequest request) {
        String query = request.getURI().getQuery();
        if (query == null) {
            return null;
        }
        for (String part : query.split("&")) {
            int idx = part.indexOf('=');
            if (idx > 0 && "token".equals(part.substring(0, idx))) {
                return part.substring(idx + 1);
            }
        }
        return null;
    }

    private void reject(ServerHttpResponse response, HttpStatus status, String reason) {
        response.setStatusCode(status);
        if (response instanceof ServletServerHttpResponse servletResponse) {
            servletResponse.getServletResponse().setHeader("X-Reject-Reason", reason);
        }
    }
}
