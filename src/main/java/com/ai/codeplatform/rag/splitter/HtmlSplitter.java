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
public class HtmlSplitter implements CodeSplitter{
    @Resource
    private ChatModel routingChatModelPrototype;

    // 识别常见的语义区域
    private static final Pattern HEADER_PATTERN = Pattern.compile("<header[^>]*>[\\s\\S]*?</header>", CASE_INSENSITIVE);
    private static final Pattern HERO_PATTERN = Pattern.compile("<section[^>]*class=\"[^\"]*hero[^\"]*\"[^>]*>[\\s\\S]*?</section>", CASE_INSENSITIVE);
    private static final Pattern FOOTER_PATTERN = Pattern.compile("<footer[^>]*>[\\s\\S]*?</footer>", CASE_INSENSITIVE);

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

        if (content.length() <= 200) {
            // 小文件直接作为一个块
            Map<String, String> fileName = Map.of("file_name", filePath, "project_type", "html");
            chunks.add(new TextSegment(content, Metadata.from(fileName)));
            return chunks;
        }

        // 大文件按 template/script/style 拆分
        addIfMatch(chunks, filePath, HEADER_PATTERN, content, "header");
        addIfMatch(chunks, filePath, HERO_PATTERN, content, "section");
        addIfMatch(chunks, filePath, FOOTER_PATTERN, content, "footer");
        if (chunks.isEmpty()) {
            chunks.add(new TextSegment(content,
                    Metadata.from(Map.of("file_name", filePath, "tag_type", "full", "project_type", "html"))));
        }
        // 对代码块做中文补充描述，方便检索
        chunks = supplementChunk(chunks);
        return chunks;
    }

    /**
     * 添加匹配的块
     *
     * @param chunks
     * @param filePath
     * @param componentPattern
     * @param content
     * @param tag
     */
    private void addIfMatch(List<TextSegment> chunks,
                            String filePath, Pattern componentPattern,
                            String content, String tag) {
        Matcher matcher = componentPattern.matcher(content);
        if (matcher.find()) {
            String templateContent = matcher.group(); // 获取匹配的完整内容
            // 创建一个TextSegment对象，并添加元数据
            Map<String, String> metadata = Map.of("file_name", filePath, "tag_type", tag, "project_type", "html");
            chunks.add(new TextSegment(templateContent, Metadata.from(metadata)));
        }
    }

    /**
     * 对代码块做中文补充描述
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
