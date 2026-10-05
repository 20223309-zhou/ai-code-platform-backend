package com.ai.codeplatform.ai.tools;

import cn.hutool.json.JSONObject;
import com.ai.codeplatform.core.builder.VueProjectBuilder;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Vue 项目构建工具
 * <p>
 * 把构建能力交给 AI：代码生成/修改完成后由 AI 自行调用构建验证，
 * 构建失败时错误日志会返回给 AI，由其分析原因、修复代码、重新构建，
 * 形成"生成 → 构建 → 修复"的闭环，替代原先"响应结束后后端盲构建"的模式。
 */
@Slf4j
@Component
public class BuildVueProjectTool extends BaseTool {

    /**
     * 疑似"缺少依赖"的关键字：跳过安装的构建失败若命中，自动重装依赖后再试一次
     */
    private static final String[] MISSING_DEPENDENCY_KEYWORDS = {
            "Cannot find module", "Can't resolve", "Failed to resolve",
            "MODULE_NOT_FOUND", "ERR_MODULE_NOT_FOUND", "ERESOLVE"
    };

    @Resource
    private VueProjectBuilder vueProjectBuilder;

    @Tool("构建当前 Vue 项目（自动执行 npm install + npm run build）。所有文件创建或修改完成后必须调用一次，验证项目可构建；若返回构建失败，必须根据错误日志定位问题、修复代码后再次调用本工具，直到构建成功")
    public String buildVueProject(@ToolMemoryId Long appId) {
        Path projectRoot = BaseTool.resolveProjectRootDir(appId);
        if (projectRoot == null) {
            return "错误: 找不到应用 " + appId + " 的项目目录";
        }
        log.info("AI 发起构建 Vue 项目, appId: {}, 目录: {}", appId, projectRoot);
        // node_modules 已存在时跳过 install，加速修复循环中的重复构建
        boolean skipInstall = Files.exists(projectRoot.resolve("node_modules"));
        VueProjectBuilder.BuildResult result = vueProjectBuilder.buildProjectWithResult(projectRoot.toString(), skipInstall);
        // 跳过安装导致的依赖缺失类失败：自动完整重装依赖再构建一次
        if (!result.success() && skipInstall && looksLikeMissingDependency(result.message())) {
            log.info("构建错误疑似缺少依赖，自动重装依赖后重试, appId: {}", appId);
            result = vueProjectBuilder.buildProjectWithResult(projectRoot.toString(), false);
        }
        if (result.success()) {
            return "✅ " + result.message() + "。项目可正常构建。";
        }
        return "❌ " + result.message() + "\n\n请根据以上错误日志定位问题（重点关注其中的文件路径和行号），" +
                "使用文件读取工具查看出错文件，使用文件修改工具修复后，重新调用 buildVueProject 验证。";
    }

    /**
     * 判断构建错误是否疑似"缺少依赖"（跳过 install 场景下的自动补救依据）
     */
    private boolean looksLikeMissingDependency(String message) {
        if (message == null) {
            return false;
        }
        for (String keyword : MISSING_DEPENDENCY_KEYWORDS) {
            if (message.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String getToolName() {
        return "buildVueProject";
    }

    @Override
    public String getDisplayName() {
        return "构建 Vue 项目";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        return String.format("""
                🛠️[工具调用] 构建项目

                正在执行 npm install + npm run build 验证项目可构建...

                """);
    }
}
