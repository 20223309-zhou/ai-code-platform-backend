package com.ai.codeplatform.ai.tools;

import cn.hutool.json.JSONObject;
import com.ai.codeplatform.constant.AppConstant;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * 工具基类
 * 定义所有工具的通用接口
 */
@Slf4j
public abstract class BaseTool {

    /**
     * 获取工具的英文名称（对应方法名）
     *
     * @return 工具英文名称
     */
    public abstract String getToolName();

    /**
     * 获取工具的中文显示名称
     *
     * @return 工具中文名称
     */
    public abstract String getDisplayName();

    /**
     * 生成工具请求时的返回值（显示给用户）
     *
     * @return 工具请求显示内容
     */
    public String generateToolRequestResponse() {
        return String.format("\n\n🛠️[选择工具] %s\n\n", getDisplayName());
    }

    /**
     * 规范化路径：去掉开头的 / 或 ./ 前缀，统一成相对路径
     * 防止 AI 传入 /package.json 等被识别为绝对路径导致写入系统根目录
     */
    protected static String normalizePath(String filePath) {
        if (filePath == null) return null;
        return filePath.replaceAll("^[/.]+", "");
    }

    /**
     * 根据 appId 查找对应的项目根目录（兼容多种生成模式：vue_project、multi_file、html）
     * 如果目录不存在，自动创建默认的 vue_project 目录作为回退
     */
    protected static Path resolveProjectRootDir(Long appId) {
        // 获得输出目录
        Path outputDir = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR);
        String[] prefixes = {"vue_project_", "multi_file_", "html_"};
        // 尝试匹配前缀
        for (String prefix : prefixes) {
            // 拼接目录的绝对路径
            Path dir = outputDir.resolve(prefix + appId);
            if (Files.exists(dir) && Files.isDirectory(dir)) {
                return dir;
            }
        }
        // 扫描所有以 _{appId} 结尾的目录
        try (Stream<Path> dirs = Files.list(outputDir)) {
            Path existing = dirs.filter(Files::isDirectory)
                    .filter(d -> d.getFileName().toString().endsWith("_" + appId))
                    .findFirst()
                    .orElse(null);
            if (existing != null) {
                return existing;
            }
        } catch (IOException e) {
            log.error("扫描输出目录失败", e);
        }
        // 都不存在时，创建默认目录作为回退（保留旧版自创建能力）
        Path fallback = outputDir.resolve("vue_project_" + appId);
        try {
            Files.createDirectories(fallback);
            log.info("自动创建项目目录: {}", fallback);
        } catch (IOException e) {
            log.error("创建项目目录失败", e);
        }
        return fallback;
    }

    /**
     * 生成工具执行结果格式（保存到数据库）
     *
     * @param arguments 工具执行参数
     * @return 格式化的工具执行结果
     */
    public abstract String generateToolExecutedResult(JSONObject arguments);
}
