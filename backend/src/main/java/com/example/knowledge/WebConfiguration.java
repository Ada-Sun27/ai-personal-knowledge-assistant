package com.example.knowledge;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class WebConfiguration {
    @Bean
    public ThreadPoolTaskExecutor answerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("answer-");
        executor.initialize();
        return executor;
    }
}
