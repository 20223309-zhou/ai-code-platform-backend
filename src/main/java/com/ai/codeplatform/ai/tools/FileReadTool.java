package com.ai.codeplatform.ai.tools;

import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件读取工具
 * 支持 AI 通过工具调用的方式读取文件内容
 */
@Slf4j
@Component
public class FileReadTool extends BaseTool {

    @Tool("读取项目目录下的文件内容，路径如 \"src/App.vue\"，不要以 / 或 ./ 开头")
    public String readFile(
            @P("文件的相对路径，如 \"src/App.vue\"，不要以 / 或 ./ 开头")
            String relativeFilePath,
            @ToolMemoryId Long appId
    ) {
        try {
            relativeFilePath = BaseTool.normalizePath(relativeFilePath);
            Path path = Paths.get(relativeFilePath);
            Path projectRoot = BaseTool.resolveProjectRootDir(appId);
            if (projectRoot == null) {
                return "错误: 找不到应用 " + appId + " 的项目目录";
            }
            path = projectRoot.resolve(relativeFilePath);
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
