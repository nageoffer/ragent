package com.nageoffer.ai.ragent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.nageoffer.ai.ragent.dao.mapper")
public class RagentCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagentCoreApplication.class, args);
    }
}
