package com.ai.codeplatform.rag.config;

import com.ai.codeplatform.rag.RagSwitchHolder;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ConditionalContentRetriever implements ContentRetriever {

    @Resource
    private EmbeddingStoreContentRetriever retriever;  // 真正的检索器

    @Override
    public List<Content> retrieve(Query query) {
        // 从当前请求上下文或应用中拿开关状态
        if (RagSwitchHolder.isEnabled()) {
            return retriever.retrieve(query);
        }
        return List.of();  // 关闭 RAG 时返回空
    }
}