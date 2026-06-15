package com.codeleon.runner.preview;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/**
 * Reverse-proxies {@code /api/v1/preview/{roomId}/**} to the room's live
 * dev-server container ({@code http://codeleon-preview-{roomId}:{port}/**}).
 *
 * <p>This endpoint is permitted without JWT (see SecurityConfig): an iframe's
 * requests do not carry the SPA's bearer token, and the deployment is reachable
 * only from the Tailscale tailnet, which is the access guard. Per-user hardening
 * (a scoped preview cookie) is a documented follow-up.
 *
 * <p>HTTP only for now; WebSocket/HMR upgrade is handled separately (B.2).
 */
@RestController
@RequiredArgsConstructor
public class PreviewProxyController {

    private static final Logger log = LoggerFactory.getLogger(PreviewProxyController.class);

    // Headers that must not be copied verbatim across a proxy hop.
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "transfer-encoding", "upgrade", "host", "content-length");

    private final PreviewService previewService;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @RequestMapping("/preview/{roomId}/**")
    public void proxy(@PathVariable UUID roomId, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        PreviewSession session = previewService.get(roomId).orElse(null);
        if (session == null) {
            writeNotice(resp, 503, "No preview running", "Start a preview from the editor.");
            return;
        }
        previewService.touch(roomId);

        // Strip the "<context>/preview/{roomId}" prefix to get the upstream path.
        String prefix = req.getContextPath() + "/preview/" + roomId;
        String rest = req.getRequestURI().substring(prefix.length());
        if (rest.isEmpty()) {
            rest = "/";
        }
        String query = req.getQueryString();
        URI upstream = URI.create("http://" + session.containerName() + ":" + session.port()
                + rest + (query != null ? "?" + query : ""));

        // Stream the request body through instead of buffering it with
        // readAllBytes() — a large upload to the preview must not sit entirely
        // in the backend heap. content-length is hop-by-hop (stripped below),
        // so the upstream just receives the body chunked.
        long contentLength = req.getContentLengthLong();
        HttpRequest.BodyPublisher bodyPublisher = contentLength == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofInputStream(() -> {
                    try {
                        return req.getInputStream();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
        HttpRequest.Builder builder = HttpRequest.newBuilder(upstream)
                .method(req.getMethod(), bodyPublisher);
        Collections.list(req.getHeaderNames()).forEach(name -> {
            if (!HOP_BY_HOP.contains(name.toLowerCase())) {
                Collections.list(req.getHeaders(name)).forEach(value -> {
                    try {
                        builder.header(name, value);
                    } catch (IllegalArgumentException ignored) {
                        // java.net.http forbids a few restricted headers — skip them.
                    }
                });
            }
        });

        try {
            HttpResponse<InputStream> upResponse = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            resp.setStatus(upResponse.statusCode());
            upResponse.headers().map().forEach((key, values) -> {
                if (!HOP_BY_HOP.contains(key.toLowerCase())) {
                    values.forEach(value -> resp.addHeader(key, value));
                }
            });
            try (InputStream in = upResponse.body(); OutputStream out = resp.getOutputStream()) {
                in.transferTo(out);
            }
        } catch (IOException ex) {
            log.debug("Preview upstream not reachable for {}", roomId, ex);
            writeNotice(resp, 502, "Preview not ready yet",
                    "The dev server is still starting or has crashed. Check the logs.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            writeNotice(resp, 502, "Preview interrupted", "Please try again.");
        }
    }

    private void writeNotice(HttpServletResponse resp, int status, String title, String detail) throws IOException {
        resp.setStatus(status);
        resp.setContentType("text/html; charset=utf-8");
        resp.getWriter().write("<!doctype html><meta charset=utf-8>"
                + "<div style=\"font-family:system-ui;padding:2rem;color:#3f3f46\">"
                + "<h3 style=\"margin:0 0 .5rem\">" + title + "</h3><p style=\"margin:0\">" + detail + "</p></div>");
    }
}
