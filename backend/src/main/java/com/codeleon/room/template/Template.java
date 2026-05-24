package com.codeleon.room.template;

import java.util.List;

/**
 * Full template definition loaded from a JSON file in classpath:templates/.
 *
 * Only file paths are persisted today — the Y.Doc that holds editor
 * content is initialised empty and the user fills it in. Pre-filling
 * the Y.Doc with text content from the template ("content seeding")
 * would require either a Java Y.Doc encoder or a per-file
 * seed_content column applied on first WebSocket connect; that is
 * tracked as a follow-up in ROADMAP section 6.
 */
public record Template(
        String id,
        String name,
        String description,
        String language,
        String category,
        String runtime,
        String packageManager,
        String defaultCommand,
        Boolean runnable,
        Boolean preview,
        List<String> services,
        List<String> tags,
        List<TemplateFile> files
) {
    public String categoryOrDefault() {
        return category == null || category.isBlank() ? "General" : category;
    }

    public boolean runnableOrDefault() {
        return Boolean.TRUE.equals(runnable);
    }

    public boolean previewOrDefault() {
        return Boolean.TRUE.equals(preview);
    }

    public List<String> servicesOrDefault() {
        return services == null ? List.of() : services;
    }

    public List<String> tagsOrDefault() {
        return tags == null ? List.of() : tags;
    }

    public record TemplateFile(String path, String content) {
    }
}
