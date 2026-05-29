package com.ai.codeplatform.rag.splitter;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.CASE_INSENSITIVE;

@Component
public class MultiSplitter implements CodeSplitter{
    @Resource
    private ChatModel routingChatModelPrototype;

    /**
     * 对完整文件做拆分，返回代码块
     *
     * @param filePath
     * @param content
     * @return
     */
    @Override
    public List<TextSegment> chunk(String filePath, String content) {
        List<TextSegment> chunks = new ArrayList<>();
        // 多文件模式一个文件直接作为一个块
        chunks.add(new TextSegment(content,
                Metadata.from(Map.of("file_name", filePath, "project_type", "multi"))));
        // 对代码块做中文补充描述，方便检索
        chunks = supplementChunk(chunks);
        return chunks;
    }


    /**
     * 对代码块做中文补充描述
     *
     * @param chunks
     * @return
     */
    private List<TextSegment> supplementChunk(List<TextSegment> chunks) {
        return chunks.stream().map(chunk -> {
            String chat = routingChatModelPrototype.chat(String.format("""
                     用一句话描述以下代码的功能（不超过30字）：
                     %s
                    """, chunk.text()));
            // 拼接代码描述和代码块
            String newText = String.format("%s:%s", chat, chunk.text());
            return new TextSegment(newText, Metadata.from(chunk.metadata().toMap()));
        }).toList();
    }
}
