package com.ai.codeplatform;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@SpringBootApplication
@MapperScan("com.ai.codeplatform.mapper")
@EnableAspectJAutoProxy(exposeProxy = true)
public class AiCodePlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiCodePlatformApplication.class, args);
    }

}
