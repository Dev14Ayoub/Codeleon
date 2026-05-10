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
        List<TemplateFile> files
) {
    public record TemplateFile(String path) {
    }
}
