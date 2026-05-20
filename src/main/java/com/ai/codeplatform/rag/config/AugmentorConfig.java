package com.ai.codeplatform.rag.config;

import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
/**
 * 检索增强器配置
 */
@Configuration
public class AugmentorConfig {

    @Resource
    private ConditionalContentRetriever conditionalContentRetriever;

    @Bean
    public RetrievalAugmentor retrievalAugmentor() {
        DefaultContentInjector injector = DefaultContentInjector.builder()
                .promptTemplate(PromptTemplate.from(
        """
        === 知识库参考（仅作风格参考，非用户已有代码）===
        {{contents}}
        
        === 用户问题 ===
        {{userMessage}}
        
        """))
                .build();
        return DefaultRetrievalAugmentor.builder()
                // 根据用户查询动态生成过滤条件
                .contentRetriever(conditionalContentRetriever)
                // 对用户提示词进行内容注入
                .contentInjector(injector)
                .build();
    }
}
