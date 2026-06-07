package com.ai.codeplatform.ai.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SVG Logo 生成工具
 * 从 Iconify 搜索图标嵌入 Logo 布局，比 AI 手写 SVG 质量稳定得多
 */
@Slf4j
@Component
public class GenerateLogoTool extends BaseTool {

    private static final String ICONIFY_API = "https://api.iconify.design/search?limit=5";
    private static final String ICONIFY_SVG = "https://api.iconify.design";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Tool("为网站生成SVG Logo图标，返回可直接嵌入HTML的SVG代码。从Iconify图标库搜索真实图标拼入Logo")
    public String generateLogoSvg(
            @P("品牌名称或Logo上显示的文字")
            String brandName,
            @P("品牌主色，十六进制格式如 #4F7CFF")
            String primaryColor,
            @P("Logo图标的关键词（必须使用英文），基于网站内容或品牌自动提取(一次调用只允许使用单个单词)，如 doraemon")
            String description
    ) {
        if (StrUtil.isBlank(brandName)) return "错误: 品牌名称不能为空";
        if (StrUtil.isBlank(primaryColor)) primaryColor = "#4F7CFF";
        log.info("生成 Logo: {}, {}, {}", brandName, primaryColor, description);
        // 1. 从 Iconify 搜索图标 SVG
        String iconSvg = fetchIconSvg(description);
        if (iconSvg == null) {
            // fallback: 首字母图标
            return generateFallbackSvg(brandName, primaryColor);
        }

        // 2. 把图标颜色的 fill/ stroke 替换为品牌主色
        iconSvg = recolorSvg(iconSvg, primaryColor);

        // 3. 从 Iconify SVG 中提取 viewBox
        String iconViewBox = extractViewBox(iconSvg, "0 0 24 24");

        // 4. 提取图标内部标签
        String iconContent = extractSvgContent(iconSvg);

        // 5. 组装完整 Logo
        return String.format("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 60" width="200" height="60">
                  <g transform="translate(8, 6) scale(2)">
                    <svg xmlns="http://www.w3.org/2000/svg" viewBox="%s" width="24" height="24" fill="%s">
                      %s
                    </svg>
                  </g>
                  <text x="66" y="40" font-family="Arial,sans-serif" font-size="24" font-weight="bold" fill="%s" dominant-baseline="middle">%s</text>
                </svg>
                """, iconViewBox, primaryColor, iconContent, primaryColor, escapeXml(brandName));
    }

    /**
     * 从 Iconify 搜索并获取 SVG 代码
     */
    private String fetchIconSvg(String keyword) {
        if (StrUtil.isBlank(keyword)) return null;
        try {
            // 搜索
            String searchUrl = ICONIFY_API + "&query=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(searchUrl))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("Iconify 搜索失败, status: {}", res.statusCode());
                return null;
            }

            String body = res.body();
            log.debug("Iconify 响应: {}", StrUtil.sub(body, 0, 200));
            JSONObject json = JSONUtil.parseObj(body);
            JSONArray icons = json.getJSONArray("icons");
            if (icons == null || icons.isEmpty()) return null;

            // 取第一个结果（返回格式为字符串 "prefix:name"）
            String firstIcon;
            try {
                firstIcon = icons.getStr(0);
            } catch (Exception e) {
                log.warn("Iconify 解析图标结果失败: {}", e.getMessage());
                return null;
            }
            if (StrUtil.isBlank(firstIcon) || !firstIcon.contains(":")) {
                log.warn("Iconify 图标格式异常: {}", firstIcon);
                return null;
            }
            String[] parts = firstIcon.split(":", 2);
            String prefix = parts[0];
            String name = parts[1];

            // 获取 SVG
            String svgUrl = ICONIFY_SVG + "/" + prefix + "/" + name + ".svg";
            HttpRequest svgReq = HttpRequest.newBuilder()
                    .uri(URI.create(svgUrl))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> svgRes = httpClient.send(svgReq, HttpResponse.BodyHandlers.ofString());
            if (svgRes.statusCode() != 200) return null;

            log.info("Iconify 图标: {} -> {}:{}", keyword, prefix, name);
            return svgRes.body();
        } catch (IOException | InterruptedException e) {
            log.warn("Iconify 请求失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 替换 SVG 中的颜色为品牌主色
     */
    private String recolorSvg(String svg, String color) {
        // 替换 fill 属性 （fill="xxx" 或 fill='xxx'）
        svg = svg.replaceAll("fill\\s*=\\s*\"[^\"]*\"", "fill=\"" + color + "\"");
        svg = svg.replaceAll("fill\\s*=\\s*'[^']*'", "fill='" + color + "'");
        svg = svg.replaceAll("fill\\s*=\\s*currentColor", "fill=\"" + color + "\"");
        // 替换 stroke 属性
        svg = svg.replaceAll("stroke\\s*=\\s*\"[^\"]*\"", "stroke=\"" + color + "\"");
        svg = svg.replaceAll("stroke\\s*=\\s*'[^']*'", "stroke='" + color + "'");
        svg = svg.replaceAll("stroke\\s*=\\s*currentColor", "stroke=\"" + color + "\"");
        return svg;
    }

    /**
     * 提取 SVG 的 viewBox
     */
    private String extractViewBox(String svg, String fallback) {
        Matcher m = Pattern.compile("viewBox\\s*=\\s*\"([^\"]+)\"").matcher(svg);
        return m.find() ? m.group(1) : fallback;
    }

    /**
     * 提取 <svg> 标签内部的内容（去掉 <svg ...> 和 </svg>）
     */
    private String extractSvgContent(String svg) {
        int start = svg.indexOf('>');
        int end = svg.lastIndexOf("</svg>");
        if (start == -1 || end == -1 || start >= end) return svg;
        return svg.substring(start + 1, end).trim();
    }

    /**
     * Fallback：首字母 + 圆底 Logo
     */
    private String generateFallbackSvg(String brandName, String primary) {
        String first = escapeXml(brandName.substring(0, 1));
        return String.format("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 60" width="200" height="60">
                  <rect x="4" y="6" width="44" height="48" rx="10" fill="%s"/>
                  <text x="26" y="34" font-family="Arial,sans-serif" font-size="22" font-weight="bold" fill="white" text-anchor="middle" dominant-baseline="middle">%s</text>
                  <text x="58" y="38" font-family="Arial,sans-serif" font-size="24" font-weight="bold" fill="%s" dominant-baseline="middle">%s</text>
                </svg>
                """, primary, first, primary, escapeXml(brandName));
    }

    private String escapeXml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    @Override
    public String getToolName() { return "generateLogoSvg"; }

    @Override
    public String getDisplayName() { return "生成Logo"; }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String brandName = arguments.getStr("brandName");
        return String.format("\n\n🎨[工具调用] %s: %s\n\n", getDisplayName(), brandName);
    }
}
