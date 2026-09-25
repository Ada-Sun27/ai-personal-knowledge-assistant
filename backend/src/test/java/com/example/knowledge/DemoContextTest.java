package com.example.knowledge;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "app.data-dir=${java.io.tmpdir}/knowledge-context-test")
@ActiveProfiles("demo")
class DemoContextTest {
    @Autowired AiGateway gateway;

    @Test
    void startsWithoutAwsCredentials() {
        assertInstanceOf(DemoGateway.class, gateway);
    }
}
