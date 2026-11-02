package com.funnywork;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.time.Clock;

@SpringBootApplication(exclude=UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class Application {
    public static void main(String[] args) { SpringApplication.run(Application.class, args); }
    @Bean Clock clock() { return Clock.systemUTC(); }
}
