package com.codeleon.runner;

import com.codeleon.common.exception.NotFoundException;
import com.codeleon.room.RoomFileService;
import com.codeleon.room.event.RoomEventService;
import com.codeleon.room.event.RoomEventType;
import com.codeleon.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/rooms/{roomId}/run")
@RequiredArgsConstructor
public class RunController {

    private final CodeRunnerService runnerService;
    private final RoomFileService roomFileService;
    private final RoomEventService roomEventService;

    @PostMapping
    public RunResult run(
            @PathVariable UUID roomId,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody RunRequest request
    ) {
        if (!roomFileService.canEdit(roomId, user)) {
            throw new NotFoundException("Room not found");
        }
        RunResult result = runnerService.run(request);
        // Record the run in the activity feed. We log the language and the
        // exit code so the feed line can read "ran code (exit 0)" — useful
        // signal without storing the whole stdout/stderr blob.
        roomEventService.emit(roomId, user, RoomEventType.CODE_RAN, Map.of(
                "language", request.language().name(),
                "exitCode", String.valueOf(result.exitCode())
        ));
        return result;
    }
}
