# Sequence — Real-time collaborative editing

How a keystroke from one user lands in another user's Monaco editor.
Captures the whole loop: REST snapshot bootstrap, WebSocket handshake,
binary CRDT relay, and debounced server-side persistence.

```mermaid
sequenceDiagram
    autonumber
    actor UA as User A (browser)
    participant FA as Frontend A<br/>(Monaco + y-monaco + Yjs)
    participant API as Backend REST API
    participant WS as CollabWebSocketHandler
    participant DB as PostgreSQL<br/>(rooms.state_update)
    participant FB as Frontend B
    actor UB as User B

    Note over UA,FB: User A enters /rooms/{id}

    FA->>API: GET /rooms/{id}/snapshot<br/>Authorization: Bearer JWT
    API->>DB: SELECT state_update FROM rooms WHERE id = ?
    DB-->>API: byte[] (Y.Doc snapshot)
    API-->>FA: 200 application/octet-stream

    FA->>FA: Y.applyUpdate(ydoc, bytes, "remote-snapshot")

    FA->>WS: WebSocket upgrade<br/>/ws/rooms/{id}?token={JWT}
    WS->>WS: CollabHandshakeInterceptor<br/>verifies JWT + canRead(roomId, user)
    WS-->>FA: 101 Switching Protocols
    FA->>FA: setIsConnected(true) + setIsReady(true)
    FA->>WS: awareness state (user id, name, color)

    Note over WS,FB: User B is already connected, sends own awareness

    WS-->>FB: peer A's awareness
    FB-->>UB: cursor/avatar of A appears in Monaco

    UA->>FA: types "fib" in editor
    FA->>FA: Yjs Y.Text mutation<br/>encodeStateAsUpdate diff
    FA->>WS: BinaryMessage (CRDT update)
    WS->>WS: relay to every peer in room except sender
    WS-->>FB: BinaryMessage
    FB->>FB: Y.applyUpdate(ydoc, update, "remote")
    FB-->>UB: Monaco re-renders with the new chars

    Note over FA,DB: Snapshot debounce — 3s after last edit

    FA->>API: PUT /rooms/{id}/snapshot<br/>application/octet-stream<br/>Y.encodeStateAsUpdate(ydoc)
    API->>API: ensureWriteAccess(room, user)
    API->>DB: UPDATE rooms SET state_update = ? WHERE id = ?
    DB-->>API: 1 row affected
    API-->>FA: 204 No Content
```

## Notes

- **Initial state is restored via REST**, not via the Yjs sync protocol.
  Our backend handler is a binary relay only — it does not run a
  server-side `Y.Doc`. This is why `useCollabRoom` flips `isReady=true`
  on the WebSocket `connected` status (the snapshot already loaded
  before the WS opened), and not on Yjs's `sync` event which never
  fires when only one peer is in the room.
- **Frame 7 is the JWT handshake check.** `CollabHandshakeInterceptor`
  pulls the token from the query string, validates it via `JwtService`,
  and stores the resolved `User` and a `canEdit` flag on the WebSocket
  session attributes. VIEWER-role peers connect successfully but the
  handler refuses to relay frames coming from them upstream.
- **Awareness uses the same WebSocket** as CRDT updates; y-monaco
  encodes peer cursor positions inside the same binary protocol.
- **Snapshot persistence is debounced** at 3000 ms in
  `useCollabRoom.ts` (`SNAPSHOT_DEBOUNCE_MS`) to avoid hammering the DB
  during sustained typing.
