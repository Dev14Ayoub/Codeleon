package com.codeleon.ai.agent;

import java.util.Map;
import java.util.UUID;

/**
 * One capability the agent can invoke at reasoning time. Implementations
 * are Spring beans — the {@link AgentToolRegistry} picks them up by type
 * and exposes them as a tool catalogue to the model.
 *
 * <p>Tools are always scoped to a {@code roomId}: the agent can never see
 * or affect another room's state. Arguments arrive as a parsed JSON
 * object straight from the model; tools are responsible for validating
 * the keys they care about and producing a safe, bounded text response.
 *
 * <p>All tools in this phase are read-only. Mutating tools (apply patch,
 * run tests) belong to a later phase with explicit sandboxing controls.
 */
public interface AgentTool {

    /** Function name the model calls — must be a valid identifier. */
    String name();

    /** One-paragraph description shown to the model. Be specific about
     *  what the tool returns and when to use it. */
    String description();

    /** JSON-Schema object for the function's arguments. Use
     *  {@code Map.of("type", "object", "properties", ..., "required", ...)} */
    Map<String, Object> parametersSchema();

    /**
     * Run the tool. Return a plain-text result the model will read back
     * as a {@code tool} message. Cap the response length yourself — the
     * agent loop will hard-truncate above {@link AgentLoop#MAX_TOOL_RESPONSE_CHARS}
     * to protect the context window, but readable summaries beat raw dumps.
     *
     * @throws ToolExecutionException for caller-visible errors (bad args,
     *         missing file). The agent loop catches it and surfaces the
     *         message to the model as a tool error so it can self-correct.
     */
    String execute(UUID roomId, Map<String, Object> arguments) throws ToolExecutionException;

    default ToolDefinition definition() {
        return new ToolDefinition(name(), description(), parametersSchema());
    }
}
