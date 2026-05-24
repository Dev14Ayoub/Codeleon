package com.codeleon.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiPropertiesTest {

    @Test
    void agentModelFallsBackToChatModelWhenBlank() {
        AiProperties.Ollama o = new AiProperties.Ollama(
                null, "qwen2.5-coder:1.5b", "", null, null);
        assertThat(o.agentModel()).isEqualTo("qwen2.5-coder:1.5b");
    }

    @Test
    void agentModelFallsBackToChatModelWhenNull() {
        AiProperties.Ollama o = new AiProperties.Ollama(
                null, "qwen2.5-coder:1.5b", null, null, null);
        assertThat(o.agentModel()).isEqualTo("qwen2.5-coder:1.5b");
    }

    @Test
    void explicitAgentModelOverridesChatModel() {
        AiProperties.Ollama o = new AiProperties.Ollama(
                null, "qwen2.5-coder:0.5b", "qwen2.5-coder:7b", null, null);
        assertThat(o.agentModel()).isEqualTo("qwen2.5-coder:7b");
        assertThat(o.chatModel()).isEqualTo("qwen2.5-coder:0.5b");
    }

    @Test
    void blankChatModelGetsDefaultAndAgentInheritsIt() {
        AiProperties.Ollama o = new AiProperties.Ollama(null, null, null, null, null);
        assertThat(o.chatModel()).isEqualTo("qwen2.5-coder:0.5b");
        assertThat(o.agentModel()).isEqualTo("qwen2.5-coder:0.5b");
    }

    @Test
    void compactConstructorAppliesAllDefaults() {
        AiProperties props = new AiProperties(true, null, null);
        assertThat(props.ollama().url()).isEqualTo("http://localhost:11434");
        assertThat(props.ollama().embedModel()).isEqualTo("nomic-embed-text");
        assertThat(props.qdrant().collection()).isEqualTo("codeleon-room-files");
    }
}
