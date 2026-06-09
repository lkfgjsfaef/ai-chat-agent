package com.niit.agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.niit.agent.mapper")
@EnableAsync
@EnableScheduling
public class AgentApplication {

    public static void main(String[] args) {
        System.setProperty("sun.net.inetaddr.ttl", "300");
        System.setProperty("sun.net.inetaddr.negative.ttl", "30");
        SpringApplication.run(AgentApplication.class, args);
    }
}
