package com.shardkv;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ShardKvApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShardKvApplication.class, args);
    }
}
