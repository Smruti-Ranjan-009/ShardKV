package com.shardkv.query;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class QueryConfiguration {

    @Bean(name = "queryExecutor")
    ThreadPoolTaskExecutor queryExecutor(QueryProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.parallelism());
        executor.setMaxPoolSize(properties.parallelism());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("shardkv-query-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
