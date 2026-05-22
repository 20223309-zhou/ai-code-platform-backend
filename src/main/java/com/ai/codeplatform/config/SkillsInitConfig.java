package com.ai.codeplatform.config;

import dev.langchain4j.skills.ClassPathSkillLoader;
import dev.langchain4j.skills.Skills;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class SkillsInitConfig {

    @Bean
    public Skills skills() {
        log.info("初始化 Skills ...");
        return Skills.from(ClassPathSkillLoader.loadSkills("skills"));

    }
}
