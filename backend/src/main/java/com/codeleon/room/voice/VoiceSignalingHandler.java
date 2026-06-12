package com.codeleon.room.voice;

import com.codeleon.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stateless WebRTC signaling relay for room voice calls. Each connection is a
 * peer; the server forwards SDP/ICE between peers (addressed by WS session id)
 * and announces joins/leaves. No media passes through the server — audio is P2P.
 *
 * <p>Protocol — client→server: {@code {"type":"signal","to":id,"data":…}},
 * {@code {"type":"leave"}}. server→client:
 * {@code {"type":"peers","peers":[{id,name}…]}},
 * {@code {"type":"peer-joined",id,name}}, {@code {"type":"peer-left",id}},
 * {@code {"type":"signal","from":id,"data":…}}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceSignalingHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;

    /** roomId → (sessionId → peer). */
    private final Map<UUID, Map<String, Peer>> rooms = new ConcurrentHashMap<>();

    private record Peer(WebSocketSession session, String name) {
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        UUID roomId = (UUID) session.getAttributes().get(VoiceHandshakeInterceptor.ATTR_ROOM_ID);
        User user = (User) session.getAttributes().get(VoiceHandshakeInterceptor.ATTR_USER);
        if (roomId == null || user == null) {
            close(session);
            return;
        }
        String name = user.getFullName();
        // The pump and inbound threads can both send — serialize via the decorator.
        WebSocketSession peerSession = new ConcurrentWebSocketSessionDecorator(session, 10_000, 256 * 1024);
        Map<String, Peer> peers = rooms.computeIfAbsent(roomId, key -> new ConcurrentHashMap<>());

        // Tell the new peer who is already in the call (it will initiate offers).
        ObjectNode list = objectMapper.createObjectNode();
        list.put("type", "peers");
        ArrayNode arr = list.putArray("peers");
        peers.forEach((id, peer) -> {
            ObjectNode entry = arr.addObject();
            entry.put("id", id);
            entry.put("name", peer.name());
        });
        send(peerSession, list);

        // Announce the new peer to everyone already in the call.
        ObjectNode joined = objectMapper.createObjectNode();
        joined.put("type", "peer-joined");
        joined.put("id", session.getId());
        joined.put("name", name);
        peers.values().forEach(peer -> send(peer.session(), joined));

        peers.put(session.getId(), new Peer(peerSession, name));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        UUID roomId = (UUID) session.getAttributes().get(VoiceHandshakeInterceptor.ATTR_ROOM_ID);
        if (roomId == null) {
            return;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(message.getPayload());
        } catch (IOException ex) {
            return;
        }
        switch (node.path("type").asText("")) {
            case "signal" -> relaySignal(roomId, session.getId(), node);
            case "leave" -> removePeer(roomId, session.getId());
            default -> {
                // ignore
            }
        }
    }

    private void relaySignal(UUID roomId, String fromId, JsonNode node) {
        String to = node.path("to").asText(null);
        if (to == null) {
            return;
        }
        Map<String, Peer> peers = rooms.get(roomId);
        if (peers == null) {
            return;
        }
        Peer target = peers.get(to);
        if (target == null) {
            return;
        }
        ObjectNode out = objectMapper.createObjectNode();
        out.put("type", "signal");
        out.put("from", fromId);
        out.set("data", node.path("data"));
        send(target.session(), out);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UUID roomId = (UUID) session.getAttributes().get(VoiceHandshakeInterceptor.ATTR_ROOM_ID);
        if (roomId != null) {
            removePeer(roomId, session.getId());
        }
    }

    private void removePeer(UUID roomId, String sessionId) {
        Map<String, Peer> peers = rooms.get(roomId);
        if (peers == null) {
            return;
        }
        Peer removed = peers.remove(sessionId);
        if (removed == null) {
            return;
        }
        ObjectNode left = objectMapper.createObjectNode();
        left.put("type", "peer-left");
        left.put("id", sessionId);
        peers.values().forEach(peer -> send(peer.session(), left));
        if (peers.isEmpty()) {
            rooms.remove(roomId);
        }
    }

    private void send(WebSocketSession session, ObjectNode payload) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (IOException ex) {
            log.debug("Failed to send voice signaling frame", ex);
        }
    }

    private void close(WebSocketSession session) {
        try {
            session.close();
        } catch (IOException ignored) {
            // already closed
        }
    }
}
