package com.ai.codeplatform.core.builder;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class VueProjectBuilder {

    /**
     * 构建结果：是否成功 + 说明信息（失败时携带错误日志，供 AI 分析原因）
     */
    public record BuildResult(boolean success, String message) {
        public static BuildResult ok(String message) {
            return new BuildResult(true, message);
        }

        public static BuildResult fail(String message) {
            return new BuildResult(false, message);
        }
    }

    /**
     * 返回给 AI 的日志最大长度：只保留尾部（错误信息通常在末尾），避免撑爆上下文
     */
    private static final int MAX_LOG_LENGTH = 4000;

    /**
     * 异步构建项目（不阻塞主流程，仅记录日志）
     *
     * @param projectPath 项目路径
     */
    public void buildProjectAsync(String projectPath) {
        // 在单独的线程中执行构建，避免阻塞主流程
        Thread.ofVirtual().name("vue-builder-" + System.currentTimeMillis()).start(() -> {
            try {
                BuildResult result = buildProjectWithResult(projectPath, false);
                if (result.success()) {
                    log.info("异步构建 Vue 项目成功: {}", projectPath);
                } else {
                    log.error("异步构建 Vue 项目失败: {}, 原因: {}", projectPath, result.message());
                }
            } catch (Exception e) {
                log.error("异步构建 Vue 项目时发生异常: {}", e.getMessage(), e);
            }
        });
    }

    /**
     * 构建 Vue 项目（兼容旧调用方，如部署流程，只关心成败）
     *
     * @param projectPath 项目根目录路径
     * @return 是否构建成功
     */
    public boolean buildProject(String projectPath) {
        return buildProjectWithResult(projectPath, false).success();
    }

    /**
     * 构建 Vue 项目并返回带日志的结果（供 AI 拿到失败原因自主修复）
     *
     * @param projectPath 项目根目录路径
     * @param skipInstall node_modules 已存在时是否跳过 npm install（加速修复循环中的重复构建）
     * @return 构建结果（失败时 message 含错误日志尾部）
     */
    public BuildResult buildProjectWithResult(String projectPath, boolean skipInstall) {
        File projectDir = new File(projectPath);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            return BuildResult.fail("项目目录不存在: " + projectPath);
        }
        // 检查 package.json 是否存在
        File packageJson = new File(projectDir, "package.json");
        if (!packageJson.exists()) {
            return BuildResult.fail("package.json 文件不存在: " + packageJson.getAbsolutePath());
        }
        log.info("开始构建 Vue 项目: {}（skipInstall={}）", projectPath, skipInstall);
        // 执行 npm install（node_modules 已存在且允许跳过时不重复安装）
        boolean canSkipInstall = skipInstall && new File(projectDir, "node_modules").exists();
        if (!canSkipInstall) {
            BuildResult installResult = executeCommand(projectDir, buildCommand("npm") + " install", 300, "npm install");
            if (!installResult.success()) {
                return BuildResult.fail("依赖安装失败（npm install）。错误日志：\n" + tail(installResult.message()));
            }
        } else {
            log.info("node_modules 已存在，跳过 npm install（如构建报缺少依赖，调用方可自动重装重试）");
        }
        // 执行 npm run build
        // 🔴 强制 --base=./ ：产物必须用相对路径引用 assets。
        // 预览走 /api/static/{dir}/dist/index.html，是子路径访问；
        // 若 index.html 写成绝对路径 /assets/xxx.css，浏览器会去站点根取 → 404（页面空白）。
        // 提示词里虽要求 base: './'，但 AI 生成存在不遵守的情况，所以命令行强制覆盖，优先级高于 vite.config.js。
        BuildResult buildResult = executeCommand(projectDir,
                buildCommand("npm") + " run build -- --base=./", 180, "npm run build");
        if (!buildResult.success()) {
            return BuildResult.fail("构建失败（npm run build）。错误日志：\n" + tail(buildResult.message()));
        }
        // 验证 dist 目录是否生成
        File distDir = new File(projectDir, "dist");
        if (!distDir.exists()) {
            return BuildResult.fail("构建命令执行成功但 dist 目录未生成: " + distDir.getAbsolutePath());
        }
        log.info("Vue 项目构建成功，dist 目录: {}", distDir.getAbsolutePath());
        normalizeAssetBase(distDir);
        return BuildResult.ok("构建成功，产物目录: dist/");
    }

    /**
     * 兜底修正 dist/index.html 里的资源引用路径。
     * <p>
     * 预览地址是 {@code /api/static/{dir}/dist/index.html}，属于子路径访问。
     * 只要 index.html 里写的是绝对路径 {@code /assets/x.css}，浏览器就会去站点根取，
     * 结果 404 + 预览空白。{@code --base=./} 已能覆盖绝大多数情况，
     * 但 AI 生成的 package.json 的 build 脚本千奇百怪（有的是 {@code vue-tsc -b && vite build}），
     * 参数可能被吞掉，所以这里再做一次幂等修正（已经是 ./assets/ 时不会改动）。
     */
    private void normalizeAssetBase(File distDir) {
        File indexHtml = new File(distDir, "index.html");
        if (!indexHtml.exists()) {
            return;
        }
        try {
            String html = Files.readString(indexHtml.toPath());
            String fixed = html.replaceAll("(src|href)=\"/assets/", "$1=\"./assets/");
            if (!fixed.equals(html)) {
                Files.writeString(indexHtml.toPath(), fixed);
                log.warn("构建产物用绝对路径引用 assets，已修正为相对路径（否则子路径预览会 404）: {}",
                        indexHtml.getAbsolutePath());
            }
        } catch (Exception e) {
            log.warn("修正 dist/index.html 资源路径失败（不影响构建结果）: {}", e.getMessage());
        }
    }

    /**
     * 执行命令并捕获完整输出
     * <p>
     * 使用 ProcessBuilder 合并 stderr 到 stdout（redirectErrorStream），
     * 只需读一个流且不会因缓冲区写满导致进程死锁。
     *
     * @param workingDir     工作目录
     * @param command        命令字符串（按空白分割）
     * @param timeoutSeconds 超时时间（秒）
     * @param displayName    日志展示用的命令名
     * @return 成功时 message 为完整输出；失败时 message 为输出或超时说明
     */
    private BuildResult executeCommand(File workingDir, String command, int timeoutSeconds, String displayName) {
        try {
            log.info("在目录 {} 中执行命令: {}", workingDir.getAbsolutePath(), command);
            ProcessBuilder processBuilder = new ProcessBuilder(command.split("\\s+"));
            processBuilder.directory(workingDir);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            StringBuilder output = new StringBuilder();
            // 独立线程读输出，避免管道缓冲区写满阻塞子进程
            Thread reader = Thread.ofVirtual().start(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        output.append(line).append('\n');
                        log.info("[{}] {}", displayName, line);
                    }
                } catch (Exception ignored) {
                    // 读流中断（如进程被强制终止）时静默结束
                }
            });

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.error("命令执行超时（{}秒），强制终止进程: {}", timeoutSeconds, command);
                return BuildResult.fail(displayName + " 执行超时（" + timeoutSeconds + " 秒），已强制终止。已有输出：\n" + output);
            }
            // 等待读线程收尾
            reader.join(5000);
            if (process.exitValue() == 0) {
                log.info("命令执行成功: {}", command);
                return BuildResult.ok(output.toString());
            }
            log.error("命令执行失败，退出码: {}", process.exitValue());
            return BuildResult.fail(output.toString());
        } catch (Exception e) {
            log.error("执行命令失败: {}, 错误信息: {}", command, e.getMessage(), e);
            return BuildResult.fail("执行 " + displayName + " 失败: " + e.getMessage());
        }
    }

    /**
     * 截取日志尾部（错误信息通常在末尾），超长时标注已截断的字符数
     */
    private static String tail(String log) {
        if (log == null || log.isEmpty()) {
            return "（无输出）";
        }
        if (log.length() <= MAX_LOG_LENGTH) {
            return log;
        }
        return "（前面已省略 " + (log.length() - MAX_LOG_LENGTH) + " 字符）\n"
                + log.substring(log.length() - MAX_LOG_LENGTH);
    }

    /**
     * Windows 下 npm 是 npm.cmd 脚本，需带扩展名才能被进程启动器识别
     */
    private String buildCommand(String baseCommand) {
        if (System.getProperty("os.name").toLowerCase().contains("windows")) {
            return baseCommand + ".cmd";
        }
        return baseCommand;
    }
}
