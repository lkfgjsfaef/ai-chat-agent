package com.niit.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class ThreadPoolConfig {

    @Bean
    public ExecutorService chatExecutor() {
        return new ThreadPoolExecutor(
                8,
                16,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(256),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    @Bean
    public ExecutorService contextExecutor() {
        return new ThreadPoolExecutor(
                6,
                12,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(128),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    @Bean
    public ExecutorService ragExecutor() {
        return new ThreadPoolExecutor(
                6,
                12,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(128),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    @Bean
    public ExecutorService summaryExecutor() {
        return new ThreadPoolExecutor(
                2,
                4,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(64),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    @Bean
    public ExecutorService toolExecutor() {
        return new ThreadPoolExecutor(
                4,
                8,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(64),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    @Bean
    public ScheduledExecutorService heartbeatExecutor() {
        return Executors.newScheduledThreadPool(2);
    }
}
