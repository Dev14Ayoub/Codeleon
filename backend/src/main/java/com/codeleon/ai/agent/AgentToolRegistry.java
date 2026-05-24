package com.codeleon.ai.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Indexes every {@link AgentTool} bean by name. Built once at startup so
 * the agent loop's per-iteration cost is a {@code HashMap.get}, not a
 * Spring lookup. Also exposes the tool catalogue in Ollama's expected
 * JSON shape so the loop doesn't have to know the wire format.
 */
@Component
public class AgentToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentToolRegistry.class);

    private final Map<String, AgentTool> byName;
    private final List<Map<String, Object>> ollamaTools;

    public AgentToolRegistry(List<AgentTool> tools) {
        Map<String, AgentTool> map = new LinkedHashMap<>();
        for (AgentTool t : tools) {
            if (map.put(t.name(), t) != null) {
                throw new IllegalStateException("Duplicate AgentTool name: " + t.name());
            }
        }
        this.byName = Map.copyOf(map);
        this.ollamaTools = buildOllamaTools(tools);
        log.info("Agent tool registry initialised with {} tools: {}",
                tools.size(), byName.keySet());
    }

    public AgentTool get(String name) {
        return byName.get(name);
    }

    public boolean has(String name) {
        return byName.containsKey(name);
    }

    /**
     * Tool catalogue in Ollama's expected request shape:
     * <pre>
     * [{"type":"function","function":{"name":..,"description":..,"parameters":..}}]
     * </pre>
     */
    public List<Map<String, Object>> toOllamaTools() {
        return ollamaTools;
    }

    private static List<Map<String, Object>> buildOllamaTools(List<AgentTool> tools) {
        return tools.stream()
                .<Map<String, Object>>map(t -> Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", t.name(),
                                "description", t.description(),
                                "parameters", t.parametersSchema()
                        )
                ))
                .toList();
    }
}
