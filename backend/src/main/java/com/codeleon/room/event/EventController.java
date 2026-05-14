package com.codeleon.room.event;

import com.codeleon.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Cross-room activity feed for the dashboard sidebar.
 *
 * Deliberately user-scoped and flat: GET /events returns the newest
 * activity across every room the caller belongs to. The optional
 * {@code since} query param (ISO-8601 instant) lets the frontend poll
 * for "anything newer than what I already show" every 30 s without
 * re-downloading the whole feed.
 */
@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
public class EventController {

    private final RoomEventService roomEventService;

    @GetMapping
    public List<RoomEventResponse> listEvents(
            @AuthenticationPrincipal User user,
            @RequestParam(name = "since", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since
    ) {
        return roomEventService.listForUser(user, since);
    }
}
