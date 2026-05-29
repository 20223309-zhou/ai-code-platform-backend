package com.ai.codeplatform.rag.splitter;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.CASE_INSENSITIVE;

@Component
public class VueSplitter implements CodeSplitter {
    @Resource
    private ChatModel routingChatModelPrototype;

    private static final Pattern COMPONENT_PATTERN =
            Pattern.compile("<template>[\\s\\S]*?</template>", CASE_INSENSITIVE);
    private static final Pattern SCRIPT_PATTERN =
            Pattern.compile("<script[^>]*>[\\s\\S]*?</script>", CASE_INSENSITIVE);
    private static final Pattern STYLE_PATTERN =
            Pattern.compile("<style[^>]*>[\\s\\S]*?</style>", CASE_INSENSITIVE);

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
            Map<String, String> fileName = Map.of("file_name", filePath, "project_type", "vue");
            chunks.add(new TextSegment(content, Metadata.from(fileName)));
            return chunks;
        }

        // 大文件按 template/script/style 拆分
        addIfMatch(chunks, filePath, COMPONENT_PATTERN, content, "template");
        addIfMatch(chunks, filePath, SCRIPT_PATTERN, content, "script");
        addIfMatch(chunks, filePath, STYLE_PATTERN, content, "style");
        if (chunks.isEmpty()) {
            // 没有匹配到任何标签（如 .js/.css 文件），整个文件作为一块
            Map<String, String> metadata = Map.of("file_name", filePath, "tag_type", "full", "project_type", "vue");
            chunks.add(new TextSegment(content, Metadata.from(metadata)));
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
            Map<String, String> metadata = Map.of("file_name", filePath, "tag_type", tag, "project_type", "vue");
            chunks.add(new TextSegment(templateContent, Metadata.from(metadata)));
        }

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
