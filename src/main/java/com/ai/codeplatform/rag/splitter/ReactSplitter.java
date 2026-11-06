package com.ai.codeplatform.rag.splitter;

import cn.hutool.core.util.StrUtil;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * React（.jsx / .tsx）代码分割器。
 * <p>
 * 与 {@link VueSplitter} 的区别：Vue 单文件组件有 {@code <template>/<script>/<style>}
 * 三个固定块，正则切分既准确又自然；React 没有这种结构标记，组件就是普通 JS 函数，
 * 硬按正则切（function / 箭头函数 / HOC 嵌套）很容易切错，反倒破坏语义。
 * <p>
 * 所以策略是：**文件级整块入库**。React 组件通常一个文件一个组件，
 * 整块保留能让检索命中时拿到完整上下文（imports、组件、导出都在）；
 * 只有超过 {@link #WHOLE_FILE_LIMIT} 的大文件才按行硬切，
 * 避免单个向量过长导致语义被稀释。
 */
@Slf4j
@Component
public class ReactSplitter implements CodeSplitter {

    @Resource
    private ChatModel routingChatModelPrototype;

    /** 整文件入库的字符上限，超过则按行切分 */
    private static final int WHOLE_FILE_LIMIT = 3000;

    /** 大文件每块的行数 */
    private static final int LINES_PER_CHUNK = 150;

    @Override
    public List<TextSegment> chunk(String filePath, String content) {
        List<TextSegment> chunks = new ArrayList<>();

        if (content.length() <= WHOLE_FILE_LIMIT) {
            chunks.add(segment(filePath, content, "full"));
        } else {
            String[] lines = content.split("\n", -1);
            for (int start = 0; start < lines.length; start += LINES_PER_CHUNK) {
                int end = Math.min(start + LINES_PER_CHUNK, lines.length);
                String part = String.join("\n", Arrays.copyOfRange(lines, start, end));
                if (StrUtil.isNotBlank(part)) {
                    chunks.add(segment(filePath, part, "lines-" + (start + 1) + "-" + end));
                }
            }
        }

        return supplementChunk(chunks);
    }

    private TextSegment segment(String filePath, String content, String tagType) {
        Map<String, String> metadata = Map.of(
                "file_name", filePath,
                "tag_type", tagType,
                "project_type", "react");
        return new TextSegment(content, Metadata.from(metadata));
    }

    /**
     * 给每个代码块补一句中文功能描述。
     * 向量模型是中文的（bge-small-zh），而代码里几乎没有中文，
     * 不补描述的话中文提问基本检索不到 —— 这一步不是锦上添花，是能否召回的关键。
     * 描述生成失败不影响入库：降级为原文，宁可少一点检索效果，也不能让整个项目入不了库。
     */
    private List<TextSegment> supplementChunk(List<TextSegment> chunks) {
        return chunks.stream().map(chunk -> {
            try {
                String desc = routingChatModelPrototype.chat(String.format("""
                        用一句话描述以下代码的功能（不超过30字）：
                        %s
                        """, chunk.text()));
                return new TextSegment(desc + ":" + chunk.text(),
                        Metadata.from(chunk.metadata().toMap()));
            } catch (Exception e) {
                log.warn("生成代码块描述失败，降级为原文入库: {}", e.getMessage());
                return chunk;
            }
        }).toList();
    }
}
