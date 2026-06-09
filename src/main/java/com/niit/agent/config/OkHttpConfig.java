package com.niit.agent.config;

import okhttp3.ConnectionPool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class OkHttpConfig {

    @Bean
    public ConnectionPool okHttpConnectionPool() {
        return new ConnectionPool(150, 3, TimeUnit.MINUTES);
    }
}
