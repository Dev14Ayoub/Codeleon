package com.codeleon.room.template;

/**
 * Lightweight view of a {@link Template} returned by GET /templates.
 *
 * The frontend dropdown only needs the metadata + a file count to
 * display each option; the file list itself is not needed until a
 * room is actually created from the template.
 */
public record TemplateSummary(
        String id,
        String name,
        String description,
        String language,
        int fileCount
) {
    public static TemplateSummary of(Template template) {
        return new TemplateSummary(
                template.id(),
                template.name(),
                template.description(),
                template.language(),
                template.files().size()
        );
    }
}
