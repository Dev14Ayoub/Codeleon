package com.codeleon.ai.agent;

import java.util.Map;

/**
 * JSON-Schema descriptor for a tool, serialised to Ollama's expected
 * {@code {type:"function", function:{name, description, parameters}}}
 * shape by {@link AgentToolRegistry#toOllamaTools()}.
 *
 * <p>{@code parametersSchema} is a JSON-Schema object — kept as a raw map
 * rather than a typed record because the schema vocabulary (required,
 * properties, types) is itself heterogeneous and not worth modelling.
 */
public record ToolDefinition(
        String name,
        String description,
        Map<String, Object> parametersSchema
) {}
