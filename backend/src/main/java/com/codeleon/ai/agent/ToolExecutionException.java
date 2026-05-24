package com.codeleon.ai.agent;

/**
 * Thrown by an {@link AgentTool} when execution fails in a way the model
 * should see — bad arguments, missing file, unsupported language. The
 * agent loop catches it and feeds the message back as a tool error so
 * the model can choose a different approach instead of crashing the turn.
 */
public class ToolExecutionException extends Exception {
    public ToolExecutionException(String message) {
        super(message);
    }

    public ToolExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
