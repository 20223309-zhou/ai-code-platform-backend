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

    /**
     * Logo 画布尺寸：图标区 48px + 文字区
     */
    private static final int LOGO_WIDTH = 200;
    private static final int LOGO_HEIGHT = 60;

    /**
     * 文字区起始 x（图标版 / 几何降级版）与字号区间
     */
    private static final int TEXT_X = 66;
    private static final int FALLBACK_TEXT_X = 58;
    private static final int TEXT_MAX_FONT = 24;
    private static final int TEXT_MIN_FONT = 12;

    /**
     * 文字区右侧留白
     */
    private static final int TEXT_RIGHT_PADDING = 10;

    private static final String DEFAULT_COLOR = "#4F7CFF";

    /**
     * 只接受 #RGB / #RRGGBB
     */
    private static final Pattern HEX_COLOR = Pattern.compile("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Tool("为网站生成SVG Logo图标，返回可直接嵌入HTML的SVG代码。从Iconify图标库搜索真实图标拼入Logo，搜不到则生成抽象几何Logo")
    public String generateLogoSvg(
            @P("品牌名称或Logo上显示的文字")
            String brandName,
            @P("品牌主色，十六进制格式如 #4F7CFF")
            String primaryColor,
            @P("Logo图标的关键词（必须使用英文），基于网站内容或品牌自动提取(一次调用只允许使用单个单词)，如 doraemon")
            String description
    ) {
        if (StrUtil.isBlank(brandName)) return "错误: 品牌名称不能为空";
        // 颜色校验 + 归一化：非法值回落默认色，过浅的值同样回落（白底上看不见）
        String color = normalizeColor(primaryColor);
        log.info("生成 Logo: {}, {}, {}", brandName, color, description);
        // 1. 从 Iconify 搜索图标 SVG（改色交给 Iconify 的 color 参数，不再用正则硬替换）
        String iconSvg = fetchIconSvg(description, color);
        if (iconSvg == null) {
            log.info("Iconify 搜不到图标，生成抽象几何 Logo");
            return generateAbstractGeometricSvg(brandName, color);
        }

        // 2. 从 Iconify SVG 中提取 viewBox
        String iconViewBox = extractViewBox(iconSvg, "0 0 24 24");

        // 3. 提取图标内部标签
        String iconContent = extractSvgContent(iconSvg);

        // 4. 品牌名按可用宽度自适应字号并截断，避免冲出画布
        int fontSize = fitFontSize(brandName, TEXT_X);
        String label = escapeXml(fitText(brandName, fontSize, TEXT_X));

        // 5. 组装完整 Logo
        return String.format("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" width="%d" height="%d">
                  <g transform="translate(8, 6) scale(2)">
                    <svg xmlns="http://www.w3.org/2000/svg" viewBox="%s" width="24" height="24" fill="%s">
                      %s
                    </svg>
                  </g>
                  <text x="%d" y="40" font-family="Arial,sans-serif" font-size="%d" font-weight="bold" fill="%s" dominant-baseline="middle">%s</text>
                </svg>
                """, LOGO_WIDTH, LOGO_HEIGHT, LOGO_WIDTH, LOGO_HEIGHT,
                iconViewBox, color, iconContent, TEXT_X, fontSize, color, label);
    }

    /**
     * 校验并归一化颜色。
     * <p>
     * 直接信任模型传来的字符串会把非法属性写进 SVG（如 "blue" / "#4F7CFF "），
     * 渲染时颜色丢失甚至整段失效，因此这里统一收敛成 #RRGGBB。
     */
    private String normalizeColor(String raw) {
        if (StrUtil.isBlank(raw)) return DEFAULT_COLOR;
        String color = raw.trim();
        if (!color.startsWith("#")) color = "#" + color;
        if (!HEX_COLOR.matcher(color).matches()) {
            log.warn("非法的 Logo 主色: {}，回落默认色 {}", raw, DEFAULT_COLOR);
            return DEFAULT_COLOR;
        }
        // #RGB -> #RRGGBB
        if (color.length() == 4) {
            char r = color.charAt(1), g = color.charAt(2), b = color.charAt(3);
            color = "#" + r + r + g + g + b + b;
        }
        // 过浅的颜色在白底页面上等于没有 Logo
        if (luminance(color) > 0.92) {
            log.warn("Logo 主色过浅: {}，回落默认色 {}", raw, DEFAULT_COLOR);
            return DEFAULT_COLOR;
        }
        return color.toUpperCase();
    }

    /**
     * 相对亮度（0~1），用于滤掉接近纯白的颜色
     */
    private double luminance(String hex) {
        int r = Integer.parseInt(hex.substring(1, 3), 16);
        int g = Integer.parseInt(hex.substring(3, 5), 16);
        int b = Integer.parseInt(hex.substring(5, 7), 16);
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
    }

    /**
     * 单字符宽度估算（以字号为 1 单位）。
     * 用统一均值会低估 "i / 空格"、高估 "A / m"，导致本可以放下的短名字被误截断。
     */
    private double charUnits(int cp) {
        if (cp > 0x2E80) return 1.0;                          // 中日韩字符
        if (cp == ' ') return 0.28;
        if ("iljtfrI.,:;'!|".indexOf(cp) >= 0) return 0.30;   // 窄字符
        if ("mwMW@%".indexOf(cp) >= 0) return 0.88;           // 宽字符
        if (cp >= '0' && cp <= '9') return 0.556;
        if (cp >= 'A' && cp <= 'Z') return 0.68;
        return 0.52;
    }

    /**
     * 估算文字宽度（以字号为 1 单位）
     */
    private double textUnits(String text) {
        double units = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            units += charUnits(cp);
            i += Character.charCount(cp);
        }
        return units;
    }

    /**
     * 按可用宽度算字号：能大则大，最小 12px
     */
    private int fitFontSize(String text, int x) {
        double available = LOGO_WIDTH - x - TEXT_RIGHT_PADDING;
        double units = Math.max(textUnits(text), 0.1);
        int size = (int) Math.floor(available / units);
        return Math.max(TEXT_MIN_FONT, Math.min(TEXT_MAX_FONT, size));
    }

    /**
     * 放得下就原样返回；放不下才按字符宽度截断并补省略号。
     * 省略号本身也占宽度，必须预留，否则“截断后”依然会溢出。
     */
    private String fitText(String text, int fontSize, int x) {
        double available = LOGO_WIDTH - x - TEXT_RIGHT_PADDING;
        // 快速路径：整串能放下就不动它（否则会给刚好放得下的短名字强加省略号）
        if (textUnits(text) * fontSize <= available) {
            return text;
        }
        double ellipsisWidth = 0.6 * fontSize;
        double used = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            double charWidth = charUnits(cp) * fontSize;
            if (used + charWidth + ellipsisWidth > available) {
                sb.append('…');
                break;
            }
            sb.appendCodePoint(cp);
            used += charWidth;
            i += Character.charCount(cp);
        }
        return sb.length() < text.length() ? sb.toString() : text;
    }

    /**
     * 从 Iconify 搜索并获取 SVG 代码。
     * <p>
     * 🔴 改色必须用 Iconify 的 ?color= 参数：它知道哪些图形是描边、哪些是填充。
     * 曾经的实现用正则把所有 fill 替换成主色，会把 fill="none" 也一起改掉，
     * 导致 Tabler / Lucide / Phosphor 这类描边图标全部变成实心色块。
     */
    private String fetchIconSvg(String keyword, String color) {
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

            // 获取 SVG（?color= 让服务端按图形类型正确着色）
            String svgUrl = ICONIFY_SVG + "/" + prefix + "/" + name + ".svg?color="
                    + URLEncoder.encode(color, StandardCharsets.UTF_8);
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
     * 抽象几何 Logo：随机生成多套几何图形组合，看起来像真正的 Logo
     */
    private String generateAbstractGeometricSvg(String brandName, String primary) {
        String first = escapeXml(brandName.substring(0, 1));
        String lighter = lightenColor(primary);
        String darker = darkenColor(primary);
        int variant = Math.abs((brandName.hashCode() * 31 + primary.hashCode()) % 6);

        String icon = switch (variant) {
            case 0 -> String.format("""
                    <polygon points="28,6 46,16 46,36 28,46 10,36 10,16" fill="url(#g)"/>
                    <polygon points="28,12 40,19 40,33 28,40 16,33 16,19" fill="none" stroke="rgba(255,255,255,0.3)" stroke-width="1.2"/>
                    <circle cx="28" cy="26" r="6" fill="rgba(255,255,255,0.25)"/>
                    """);
            case 1 -> String.format("""
                    <circle cx="18" cy="30" r="16" fill="%s" opacity="0.3"/>
                    <circle cx="34" cy="30" r="16" fill="%s" opacity="0.5"/>
                    <circle cx="26" cy="30" r="14" fill="url(#g)"/>
                    <text x="26" y="35" font-family="Arial,sans-serif" font-size="14" font-weight="bold" fill="white" text-anchor="middle" dominant-baseline="middle">%s</text>
                    """, primary, primary, first);
            case 2 -> String.format("""
                    <rect x="8" y="10" width="36" height="40" rx="6" fill="url(#g)" transform="rotate(15, 26, 30)"/>
                    <rect x="14" y="16" width="24" height="28" rx="4" fill="none" stroke="rgba(255,255,255,0.35)" stroke-width="1.5" transform="rotate(15, 26, 30)"/>
                    """);
            case 3 -> String.format("""
                    <path d="M26,4 L44,16 L44,36 L26,48 L8,36 L8,16 Z" fill="url(#g)"/>
                    <path d="M26,10 L38,18 L38,34 L26,42 L14,34 L14,18 Z" fill="%s" opacity="0.5"/>
                    """, lighter);
            case 4 -> String.format("""
                    <circle cx="26" cy="30" r="22" fill="none" stroke="url(#g)" stroke-width="3"/>
                    <circle cx="26" cy="30" r="14" fill="url(#g)" opacity="0.8"/>
                    <circle cx="26" cy="30" r="6" fill="white" opacity="0.3"/>
                    """);
            default -> String.format("""
                    <rect x="6" y="8" width="40" height="44" rx="8" fill="url(#g)"/>
                    <rect x="14" y="16" width="24" height="28" rx="4" fill="none" stroke="rgba(255,255,255,0.25)" stroke-width="1"/>
                    <circle cx="14" cy="16" r="3" fill="white" opacity="0.3"/>
                    <circle cx="38" cy="44" r="4" fill="white" opacity="0.2"/>
                    """);
        };
        int fontSize = fitFontSize(brandName, FALLBACK_TEXT_X);
        String label = escapeXml(fitText(brandName, fontSize, FALLBACK_TEXT_X));
        return String.format("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" width="%d" height="%d">
                  <defs>
                    <linearGradient id="g" x1="0%%" y1="0%%" x2="100%%" y2="100%%">
                      <stop offset="0%%" stop-color="%s"/>
                      <stop offset="100%%" stop-color="%s"/>
                    </linearGradient>
                    <filter id="s">
                      <feDropShadow dx="1" dy="2" stdDeviation="2" flood-opacity="0.25"/>
                    </filter>
                  </defs>
                  <g filter="url(#s)">
                    %s
                  </g>
                  <text x="%d" y="38" font-family="Arial,sans-serif" font-size="%d" font-weight="bold" fill="%s" dominant-baseline="middle">%s</text>
                </svg>
                """, LOGO_WIDTH, LOGO_HEIGHT, LOGO_WIDTH, LOGO_HEIGHT,
                lighter, darker, icon, FALLBACK_TEXT_X, fontSize, primary, label);
    }

    private String lightenColor(String hex) {
        if (hex == null || !hex.startsWith("#") || hex.length() < 7) return "#7FAAFF";
        try {
            int r = Integer.parseInt(hex.substring(1, 3), 16);
            int g = Integer.parseInt(hex.substring(3, 5), 16);
            int b = Integer.parseInt(hex.substring(5, 7), 16);
            r = Math.min(255, (int) (r + (255 - r) * 0.6));
            g = Math.min(255, (int) (g + (255 - g) * 0.6));
            b = Math.min(255, (int) (b + (255 - b) * 0.6));
            return String.format("#%02X%02X%02X", r, g, b);
        } catch (Exception e) {
            return "#7FAAFF";
        }
    }

    private String darkenColor(String hex) {
        if (hex == null || !hex.startsWith("#") || hex.length() < 7) return "#2D4FC7";
        try {
            int r = Integer.parseInt(hex.substring(1, 3), 16);
            int g = Integer.parseInt(hex.substring(3, 5), 16);
            int b = Integer.parseInt(hex.substring(5, 7), 16);
            r = Math.max(0, (int) (r * 0.5));
            g = Math.max(0, (int) (g * 0.5));
            b = Math.max(0, (int) (b * 0.5));
            return String.format("#%02X%02X%02X", r, g, b);
        } catch (Exception e) {
            return "#2D4FC7";
        }
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
