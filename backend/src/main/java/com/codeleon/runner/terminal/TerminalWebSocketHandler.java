package com.codeleon.runner.terminal;

import com.codeleon.common.exception.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bridges a browser xterm.js terminal to a sandboxed bash process.
 *
 * <p>Wire protocol (JSON text frames):
 * <ul>
 *   <li>client → server: {@code {"type":"init","files":[{path,text}...]}},
 *       {@code {"type":"stdin","data":"..."}},
 *       {@code {"type":"signal","data":"SIGINT"}}</li>
 *   <li>server → client: {@code {"type":"ready"}},
 *       {@code {"type":"output","data":"..."}},
 *       {@code {"type":"exit","code":N}},
 *       {@code {"type":"error","data":"..."}}</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TerminalWebSocketHandler extends TextWebSocketHandler {

    private final TerminalSessionService sessionService;
    private final ObjectMapper objectMapper;

    private final Map<String, WebSocketSession> wsSessions = new ConcurrentHashMap<>();
    private final Map<String, TerminalSession> terminals = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> started = new ConcurrentHashMap<>();
    private final Map<String, UUID> roomIds = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // The pump thread and the inbound message thread can both write — wrap
        // in a concurrent decorator so sends are serialized and buffered.
        WebSocketSession decorated = new ConcurrentWebSocketSessionDecorator(session, 10_000, 1024 * 1024);
        wsSessions.put(session.getId(), decorated);
        started.put(session.getId(), new AtomicBoolean(false));
        Object roomId = session.getAttributes().get(TerminalHandshakeInterceptor.ATTR_ROOM_ID);
        if (roomId instanceof UUID uuid) {
            roomIds.put(session.getId(), uuid);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String id = session.getId();
        WebSocketSession ws = wsSessions.getOrDefault(id, session);
        JsonNode node;
        try {
            node = objectMapper.readTree(message.getPayload());
        } catch (IOException ex) {
            return;
        }
        switch (node.path("type").asText("")) {
            case "init" -> handleInit(id, ws, node);
            case "stdin" -> handleStdin(id, node);
            case "sync" -> sessionService.resync(id, parseFiles(node));
            case "signal" -> sessionService.sendSignal(id, node.path("data").asText("SIGINT"));
            default -> {
                // ignore unknown frame types
            }
        }
    }

    /** Extracts the {path,text} file list from an init/sync frame. */
    private List<WorkspaceMaterializer.FileEntry> parseFiles(JsonNode node) {
        List<WorkspaceMaterializer.FileEntry> files = new ArrayList<>();
        JsonNode filesNode = node.path("files");
        if (filesNode.isArray()) {
            for (JsonNode f : filesNode) {
                String path = f.path("path").asText(null);
                if (path != null && !path.isBlank()) {
                    files.add(new WorkspaceMaterializer.FileEntry(path, f.path("text").asText("")));
                }
            }
        }
        return files;
    }

    private void handleInit(String id, WebSocketSession ws, JsonNode node) {
        AtomicBoolean flag = started.get(id);
        if (flag == null || !flag.compareAndSet(false, true)) {
            return; // a terminal was already started for this connection
        }

        List<WorkspaceMaterializer.FileEntry> files = parseFiles(node);

        TerminalSession terminal;
        try {
            terminal = sessionService.create(id, roomIds.get(id), files);
        } catch (BadRequestException ex) {
            sendType(ws, "error", ex.getMessage());
            close(ws);
            return;
        }
        terminals.put(id, terminal);
        sendType(ws, "ready", "");
        startPump(id, ws, terminal);
    }

    private void startPump(String id, WebSocketSession ws, TerminalSession terminal) {
        Thread pump = new Thread(() -> {
            int limit = sessionService.maxOutputBytes();
            int total = 0;
            byte[] buffer = new byte[4096];
            try (InputStream out = terminal.process().getInputStream()) {
                int read;
                while ((read = out.read(buffer)) != -1) {
                    sendType(ws, "output", new String(buffer, 0, read, StandardCharsets.UTF_8));
                    total += read;
                    if (total >= limit) {
                        sendType(ws, "output", "\r\n[output truncated — terminal closed]\r\n");
                        break;
                    }
                }
            } catch (IOException ex) {
                log.debug("Terminal pump ended for {}", id, ex);
            }

            int exit = -1;
            try {
                terminal.process().waitFor();
                exit = terminal.process().exitValue();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (IllegalThreadStateException ignored) {
                // process still running (e.g. truncation break) — leave exit -1
            }
            sendExit(ws, exit);
            sessionService.terminate(id);
            close(ws);
        }, "codeleon-term-pump-" + id);
        pump.setDaemon(true);
        pump.start();
    }

    private void handleStdin(String id, JsonNode node) {
        TerminalSession terminal = terminals.get(id);
        if (terminal == null) {
            return;
        }
        String data = node.path("data").asText("");
        try {
            OutputStream stdin = terminal.process().getOutputStream();
            stdin.write(data.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            sessionService.touch(id);
        } catch (IOException ex) {
            log.debug("Failed to write to terminal stdin {}", id, ex);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String id = session.getId();
        wsSessions.remove(id);
        terminals.remove(id);
        started.remove(id);
        roomIds.remove(id);
        sessionService.terminate(id);
    }

    private void sendType(WebSocketSession ws, String type, String data) {
        ObjectNode obj = objectMapper.createObjectNode();
        obj.put("type", type);
        obj.put("data", data);
        send(ws, obj);
    }

    private void sendExit(WebSocketSession ws, int code) {
        ObjectNode obj = objectMapper.createObjectNode();
        obj.put("type", "exit");
        obj.put("code", code);
        send(ws, obj);
    }

    private void send(WebSocketSession ws, ObjectNode payload) {
        try {
            ws.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (IOException ex) {
            log.debug("Failed to send terminal frame", ex);
        }
    }

    private void close(WebSocketSession ws) {
        try {
            ws.close();
        } catch (IOException ignored) {
            // already closed
        }
    }
}
