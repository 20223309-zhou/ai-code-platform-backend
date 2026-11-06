package com.ai.codeplatform.rag.config;

import cn.hutool.core.util.StrUtil;
import com.ai.codeplatform.rag.RagSwitchHolder;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15.BgeSmallZhV15EmbeddingModel;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Configuration
public class RetrieverConfig {
    @Resource
    private EmbeddingStore<TextSegment> embeddingStore;
    @Resource
    private BgeSmallZhV15EmbeddingModel embeddingModel;

    @Bean
    public EmbeddingStoreContentRetriever retriever() {
        return EmbeddingStoreContentRetriever.builder()
                /*
                 * 按「当前应用的技术栈」过滤。
                 *
                 * 知识库里混存了所有项目（html / multi / vue / react）的代码，
                 * 不过滤的后果很严重：React 项目会检索到 Vue 项目的代码作为
                 * "风格参考"注入提示词，模型看到满屏 v-if / @click 就很容易跟着写，
                 * 而这种串味往往能通过构建、只在运行时白屏，极难排查。
                 *
                 * 原来的实现是拿用户提示词做 contains("vue") 文本匹配，有两个硬伤：
                 *   1. 用户完全可能不提框架（"做一个音乐播放器"），此时返回 null = 不过滤
                 *   2. 大小写敏感，用户写 "Vue" 或 "React" 就匹配不上
                 * 所以改成优先读请求上下文里的真实类型（Facade 写入），文本猜测只作兜底。
                 */
                .dynamicFilter(query -> {
                    String projectType = RagSwitchHolder.getProjectType();
                    if (StrUtil.isNotBlank(projectType)) {
                        return metadataKey("project_type").isEqualTo(projectType);
                    }
                    // 兜底：拿不到上下文时（例如非代码生成场景触发了检索），退回文本猜测
                    String text = query.text() == null ? "" : query.text().toLowerCase();
                    if (text.contains("vue")) {
                        return metadataKey("project_type").isEqualTo("vue");
                    }
                    if (text.contains("react")) {
                        return metadataKey("project_type").isEqualTo("react");
                    }
                    if (text.contains("html")) {
                        return metadataKey("project_type").isEqualTo("html");
                    }
                    if (text.contains("multi")) {
                        return metadataKey("project_type").isEqualTo("multi");
                    }
                    return null;
                })
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .maxResults(5)
                .minScore(0.75)
                .displayName("retriever")
                .build();
    }
}
