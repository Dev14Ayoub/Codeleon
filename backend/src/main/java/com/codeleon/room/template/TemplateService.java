package com.codeleon.room.template;

import com.codeleon.common.exception.NotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads every classpath:templates/*.json file at startup, validates
 * the shape, and exposes them through a small lookup API. The catalogue
 * is immutable for the lifetime of the JVM — templates are part of the
 * shipped artifact, not user data, so a Map.copyOf snapshot is
 * sufficient and lets every read path skip locking.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TemplateService {

    private static final String CLASSPATH_PATTERN = "classpath:templates/*.json";

    private final ObjectMapper objectMapper;
    private Map<String, Template> templates = Map.of();

    @PostConstruct
    void loadCatalogue() {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Map<String, Template> loaded = new LinkedHashMap<>();
        try {
            Resource[] resources = resolver.getResources(CLASSPATH_PATTERN);
            // Sort by filename so the dropdown order is deterministic across
            // OS file systems — Windows returns directory entries in a
            // different order from Linux and we want both to produce the
            // same UX in the demo.
            java.util.Arrays.sort(resources, java.util.Comparator.comparing(r -> {
                String filename = r.getFilename();
                return filename == null ? "" : filename;
            }));

            for (Resource resource : resources) {
                Template template;
                try (var is = resource.getInputStream()) {
                    template = objectMapper.readValue(is, Template.class);
                }
                validate(template, resource.getFilename());
                if (loaded.put(template.id(), template) != null) {
                    throw new IllegalStateException("Duplicate template id: " + template.id());
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load templates from classpath:templates/", ex);
        }
        templates = Collections.unmodifiableMap(loaded);
        log.info("Loaded {} project templates: {}", templates.size(), templates.keySet());
    }

    public List<TemplateSummary> list() {
        return templates.values().stream().map(TemplateSummary::of).toList();
    }

    /**
     * Returns the full template for the given id. Throws NotFound so
     * the caller can let the GlobalExceptionHandler translate it to a
     * 404 — passing a missing id from the create-room endpoint should
     * surface a clean error rather than crashing.
     */
    public Template require(String id) {
        Template template = templates.get(id);
        if (template == null) {
            throw new NotFoundException("Template not found: " + id);
        }
        return template;
    }

    private void validate(Template template, String filename) {
        if (template == null
                || template.id() == null || template.id().isBlank()
                || template.name() == null || template.name().isBlank()
                || template.files() == null || template.files().isEmpty()) {
            throw new IllegalStateException("Invalid template in " + filename + ": id, name, and at least one file are required");
        }
        for (Template.TemplateFile file : template.files()) {
            if (file.path() == null || file.path().isBlank()) {
                throw new IllegalStateException("Invalid template " + template.id() + ": every file needs a path");
            }
        }
    }
}
