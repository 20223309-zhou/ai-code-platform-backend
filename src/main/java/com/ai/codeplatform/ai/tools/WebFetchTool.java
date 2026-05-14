package com.ai.codeplatform.ai.tools;

import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

@Slf4j
@Component
public class WebFetchTool extends BaseTool {

    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "localhost", "127.0.0.1", "0.0.0.0",
            "10.", "172.16.", "172.17.", "172.18.", "172.19.",
            "172.20.", "172.21.", "172.22.", "172.23.", "172.24.",
            "172.25.", "172.26.", "172.27.", "172.28.", "172.29.",
            "172.30.", "172.31.", "192.168."
    );

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Tool("获取指定URL的网页内容，返回纯文本。用于分析网站设计风格、布局、颜色、交互效果等")
    public String fetchWebPage(
            @P("要访问的完整URL，必须以http://或https://开头") String url,
            @ToolMemoryId Long appId
    ) {
        // 1. 基本校验
        if (url == null || url.isBlank()) {
            return "错误：URL不能为空";
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "错误：URL必须以http://或https://开头";
        }

        // 2. 安全校验：禁止访问内网地址
        try {
            URI uri = URI.create(url);
            String host = uri.getHost().toLowerCase();
            for (String blocked : BLOCKED_HOSTS) {
                if (host.equals(blocked) || host.startsWith(blocked) || host.endsWith(blocked)) {
                    return "错误：不允许访问内网地址";
                }
            }
        } catch (Exception e) {
            return "错误：URL格式无效";
        }

        // 3. 发送HTTP请求
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (compatible; AiCodeBot/1.0)")
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return "错误：服务器返回状态码 " + response.statusCode();
            }

            // 4. 提取内容
            String body = response.body();
            String text = extractText(body);

            // 5. 限制长度
            int maxLength = 8000;
            if (text.length() > maxLength) {
                text = text.substring(0, maxLength) + "\n\n...(内容过长，已截断至 " + maxLength + " 字符)";
            }

            return text;

        } catch (java.net.ConnectException e) {
            return "错误：无法连接到服务器，请检查URL是否正确";
        } catch (java.net.http.HttpTimeoutException e) {
            return "错误：请求超时";
        } catch (Exception e) {
            log.error("获取网页失败: {}", url, e);
            return "错误：获取网页失败 - " + e.getMessage();
        }
    }

    /**
     * 从HTML中提取纯文本和样式信息
     */
    private String extractText(String html) {
        // 提取 <style> 标签中的 CSS
        StringBuilder result = new StringBuilder();
        java.util.regex.Matcher styleMatcher = java.util.regex.Pattern
                .compile("<style[^>]*>(.*?)</style>", java.util.regex.Pattern.DOTALL)
                .matcher(html);
        while (styleMatcher.find()) {
            String css = styleMatcher.group(1).trim();
            if (!css.isEmpty()) {
                result.append("[CSS样式]\n").append(css.substring(0, Math.min(css.length(), 1000))).append("\n\n");
            }
        }

        // 提取 <title>
        java.util.regex.Matcher titleMatcher = java.util.regex.Pattern
                .compile("<title[^>]*>(.*?)</title>", java.util.regex.Pattern.DOTALL)
                .matcher(html);
        if (titleMatcher.find()) {
            result.append("标题: ").append(titleMatcher.group(1).trim()).append("\n\n");
        }

        // 去除所有 HTML 标签，保留纯文本
        String text = html
                .replaceAll("<script[^>]*>.*?</script>", "")
                .replaceAll("<style[^>]*>.*?</style>", "")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();

        result.append("[页面内容]\n");

        // 提取前 4000 字符
        result.append(text.substring(0, Math.min(text.length(), 4000)));

        return result.toString();
    }

    @Override
    public String getToolName() {
        return "fetchWebPage";
    }

    @Override
    public String getDisplayName() {
        return "获取网页内容";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String url = arguments.getStr("url");
        return String.format("\n\n🌐[工具调用] 获取网页内容 %s\n\n", url);
    }
}