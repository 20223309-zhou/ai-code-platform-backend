package com.ai.codeplatform.ai.tools;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ai.codeplatform.core.builder.VueProjectBuilder;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

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
     * 疑似"缺少依赖"的关键字。
     * <p>
     * 匹配前统一转小写：Vite 的报错是 {@code [vite]: Rollup failed to resolve import "x" from "y"}
     * （小写 f），而旧代码里写的是 {@code "Failed to resolve"}（大写 F），
     * 大小写敏感导致这类失败从来命中不了，也就从来不会触发自动重装。
     */
    private static final String[] MISSING_DEPENDENCY_KEYWORDS = {
            "cannot find module",
            "can't resolve",
            "failed to resolve",        // Vite：Rollup failed to resolve import "element-plus" from "..."
            "could not resolve",        // Rollup / esbuild：Could not resolve "element-plus"
            "cannot find package",
            "module_not_found",
            "err_module_not_found",
            "eresolve"
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

        // 是否跳过 npm install：
        // 不能只看 node_modules 是否存在。AI 在修复循环里会新增依赖（如 element-plus），
        // 此时 node_modules 已存在但缺少新包，跳过安装会直接让 vite build 报
        // "failed to resolve import"，AI 拿不到任何"可以装"的信号，只能自己绕过
        // （改用 CDN / importmap，产物在无外网环境下白屏）。
        // 所以判断依据改为：package.json 声明的依赖是否都已经落地。
        boolean skipInstall = !hasMissingDependencies(projectRoot);

        VueProjectBuilder.BuildResult result = vueProjectBuilder.buildProjectWithResult(projectRoot.toString(), skipInstall);
        // 兜底：已经装齐仍因依赖类问题失败（版本冲突、包名写错、lock 损坏），强制重装一次
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
     * 检查 {@code package.json} 里声明的依赖是否都已在 {@code node_modules} 落地。
     * <p>
     * 比"匹配构建报错文案"可靠得多：不依赖 Vite/Rollup 的错误格式，也不受大小写影响。
     * 注意 npm 在版本冲突时会把包放进嵌套的 node_modules，这里只查顶层；
     * 误判为"缺"的代价是多执行一次幂等的 {@code npm install}，可以接受。
     *
     * @return true 表示需要执行 npm install
     */
    private boolean hasMissingDependencies(Path projectRoot) {
        Path nodeModules = projectRoot.resolve("node_modules");
        if (!Files.exists(nodeModules)) {
            return true;    // 一次都没装过
        }
        Path packageJson = projectRoot.resolve("package.json");
        if (!Files.exists(packageJson)) {
            // 没有 package.json 就无从判断，交给构建阶段报错
            return false;
        }
        try {
            JSONObject json = JSONUtil.parseObj(Files.readString(packageJson, StandardCharsets.UTF_8));
            return anyMissing(nodeModules, json.getJSONObject("dependencies"))
                    || anyMissing(nodeModules, json.getJSONObject("devDependencies"));
        } catch (Exception e) {
            // 读不出来宁可多装一次
            log.warn("解析 package.json 失败，按需重装处理: {}", packageJson, e);
            return true;
        }
    }

    /**
     * 判断某个依赖块里是否有包没装在 node_modules 下（@scope/pkg 会被正确解析成 node_modules/@scope/pkg）
     */
    private boolean anyMissing(Path nodeModules, JSONObject dependencies) {
        if (dependencies == null || dependencies.isEmpty()) {
            return false;
        }
        for (String packageName : dependencies.keySet()) {
            if (!Files.exists(nodeModules.resolve(packageName))) {
                log.info("检测到未安装的依赖: {}（package.json 已声明，node_modules 缺失），将执行 npm install", packageName);
                return true;
            }
        }
        return false;
    }

    /**
     * 判断构建错误是否疑似"缺少依赖"（跳过 install 场景下的自动补救依据）
     */
    private boolean looksLikeMissingDependency(String message) {
        if (message == null) {
            return false;
        }
        String lowerCaseMessage = message.toLowerCase(Locale.ROOT);
        for (String keyword : MISSING_DEPENDENCY_KEYWORDS) {
            if (lowerCaseMessage.contains(keyword)) {
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
