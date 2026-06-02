package com.yuyin.asr;

import com.yuyin.asr.config.AsrProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AsrProperties.class)
public class AsrProxyApplication {
    public static void main(String[] args) {
        SpringApplication.run(AsrProxyApplication.class, args);
    }
}
