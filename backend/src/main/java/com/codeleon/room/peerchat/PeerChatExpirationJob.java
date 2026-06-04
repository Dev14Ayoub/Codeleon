package com.codeleon.room.peerchat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Hourly job that purges voice messages whose TTL has elapsed.
 *
 * <p>Voice messages are stored as audio attachments on
 * {@code room_peer_chat_messages} with a non-null {@code expires_at}
 * column. The repository hits a partial index that only contains
 * expiring rows, so the cleanup work is proportional to the number of
 * expired messages, not the table size.
 *
 * <p>Cron is offset to {@code hh:05} so the burst of work does not land
 * at the same instant as every other top-of-the-hour task in the JVM.
 * The job is transactional so a partial failure does not leave the
 * table in a half-deleted state.
 *
 * <p>For demos, lower {@code app.voice.ttl-hours} in
 * {@code application.yml} — the row is still removed by this job, just
 * sooner. There is no separate "demo job".
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PeerChatExpirationJob {

    private final RoomPeerChatMessageRepository repository;

    @Scheduled(cron = "0 5 * * * *")
    @Transactional
    public void purgeExpired() {
        Instant cutoff = Instant.now();
        int deleted = repository.deleteExpired(cutoff);
        if (deleted > 0) {
            log.info("Purged {} expired peer chat messages (cutoff={})", deleted, cutoff);
        }
    }
}
