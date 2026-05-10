package com.codeleon.room.template;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only endpoint for the templates dropdown on the dashboard.
 * Templates are a shipped artifact, not user data, so the controller
 * is intentionally minimal: just GET /templates returning the summary
 * list. The full template (with file paths) is consumed server-side
 * via {@link TemplateService#require(String)} when a room is created.
 */
@RestController
@RequestMapping("/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    @GetMapping
    public List<TemplateSummary> listTemplates() {
        return templateService.list();
    }
}
