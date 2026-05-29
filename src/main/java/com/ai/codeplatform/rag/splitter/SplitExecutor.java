package com.ai.codeplatform.rag.splitter;
import com.ai.codeplatform.constant.RagConstant;
import dev.langchain4j.data.segment.TextSegment;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class SplitExecutor {

    @Resource
    private MultiSplitter multiSplitter;
    @Resource
    private VueSplitter vueSplitter;
    @Resource
    private HtmlSplitter htmlSplitter;

    public List<TextSegment> chunk(String filePath, String content) {
        Path outputRoot = Paths.get(RagConstant.RAG_LOAD_DIRECTORY_PATH);
        Path fileAbsPath = Paths.get(filePath).toAbsolutePath();
        //获取相对路径 vue_project_414259239185522688\src\pages\About.vue
        Path relative = outputRoot.relativize(fileAbsPath);
        //获取目录名 vue_project_414259239185522688
        String dirName = relative.getName(0).toString();
        String codeGenType = dirName.split("_")[0].toLowerCase();
        return switch (codeGenType) {
            case "multi" -> multiSplitter.chunk(filePath, content);
            case "vue" -> vueSplitter.chunk(filePath, content);
            case "html" -> htmlSplitter.chunk(filePath, content);
            default -> {
                log.info("不支持的文件类型,codeGenType={}", codeGenType);
                yield new ArrayList<>();
            }
        };
    }
}
