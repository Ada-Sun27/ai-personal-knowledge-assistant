package com.example.knowledge;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Wiring only: dummy credentials instantiate real SDK clients; no inference request is made. */
@SpringBootTest(
        properties = {
            "app.data-dir=${java.io.tmpdir}/knowledge-bedrock-context-test",
            "spring.ai.bedrock.aws.access-key=test",
            "spring.ai.bedrock.aws.secret-key=test"
        })
@ActiveProfiles("bedrock")
class BedrockContextTest {
    @Autowired AiGateway gateway;
    @Autowired org.springframework.ai.chat.model.ChatModel chatModel;

    @Test
    void wiresRealSpringAiProvider() {
        assertInstanceOf(BedrockGateway.class, gateway);
        assertEquals("amazon.nova-lite-v1:0", chatModel.getDefaultOptions().getModel());
    }
}
