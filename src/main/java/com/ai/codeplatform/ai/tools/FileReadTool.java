package com.ai.codeplatform.ai.tools;

import cn.hutool.json.JSONObject;
import com.ai.codeplatform.constant.AppConstant;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * 文件读取工具
 * 支持 AI 通过工具调用的方式读取文件内容
 */
@Slf4j
@Component
public class FileReadTool extends BaseTool {

    @Tool("读取指定路径的文件内容")
    public String readFile(
            @P("文件的相对路径")
            String relativeFilePath,
            @ToolMemoryId Long appId
    ) {
        try {
            Path path = Paths.get(relativeFilePath);
            if (!path.isAbsolute()) {
                // 扫描 tmp/code_output 下所有以 _{appId} 结尾的目录
                Path outputDir = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR);
                try (Stream<Path> dirs = Files.list(outputDir)) {
                    Path projectRoot = dirs
                            .filter(Files::isDirectory)
                            .filter(d -> d.getFileName().toString().endsWith("_" + appId))
                            .findFirst()
                            .orElse(outputDir.resolve("vue_project_" + appId));
                    path = projectRoot.resolve(relativeFilePath);
                } catch (IOException e) {
                    path = outputDir.resolve("vue_project_" + appId).resolve(relativeFilePath);
                }
            }
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                return "错误：文件不存在或不是文件 - " + relativeFilePath;
            }
            return Files.readString(path);
        } catch (IOException e) {
            String errorMessage = "读取文件失败: " + relativeFilePath + ", 错误: " + e.getMessage();
            log.error(errorMessage, e);
            return errorMessage;
        }
    }

    @Override
    public String getToolName() {
        return "readFile";
    }

    @Override
    public String getDisplayName() {
        return "读取文件";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String relativeFilePath = arguments.getStr("relativeFilePath");
        return String.format("\uD83D\uDCC4[工具调用] %s %s", getDisplayName(), relativeFilePath);
    }
}
