package com.ai.codeplatform.ai.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SVG Logo 生成工具
 * 根据品牌名称和配色生成矢量 Logo
 */
@Slf4j
@Component
public class GenerateLogoTool extends BaseTool {

    @Tool("为网站生成SVG Logo图标，返回可直接嵌入HTML的SVG代码。每次生成一个Logo即可，不要把多个选项拼接在一起")
    public String generateLogoSvg(
            @P("品牌名称或Logo上显示的文字")
            String brandName,
            @P("品牌主色，十六进制格式如 #4F7CFF")
            String primaryColor,
            @P("Logo风格：minimal(简约文字)、geometric(几何图形)、rounded(圆润亲和)、modern(现代感)、classic(经典徽章)、tech(科技感)")
            String style
    ) {
        if (StrUtil.isBlank(brandName)) {
            return "错误: 品牌名称不能为空";
        }
        if (StrUtil.isBlank(primaryColor)) {
            primaryColor = "#4F7CFF";
        }
        if (StrUtil.isBlank(style)) {
            style = "minimal";
        }

        // 自动生成辅助色（加深30%用于渐变）
        String darker = darkenColor(primaryColor);

        String svg = switch (style) {
            case "geometric" -> generateGeometricSvg(brandName, primaryColor, darker);
            case "rounded" -> generateRoundedSvg(brandName, primaryColor, darker);
            case "modern" -> generateModernSvg(brandName, primaryColor, darker);
            case "classic" -> generateClassicSvg(brandName, primaryColor, darker);
            case "tech" -> generateTechSvg(brandName, primaryColor, darker);
            default -> generateMinimalSvg(brandName, primaryColor, darker);
        };

        log.info("生成 Logo: brandName={}, style={}, color={}", brandName, style, primaryColor);
        return svg;
    }

    // ==================== 6 种丰富风格 ====================

    /**
     * 简约文字 Logo：渐变图标 + 品牌名
     */
    private String generateMinimalSvg(String brandName, String primary, String darker) {
        String firstChar = escapeXml(brandName.substring(0, 1));
        return String.format("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 60" width="200" height="60">
                  <defs>
                    <linearGradient id="g" x1="0%%" y1="0%%" x2="100%%" y2="100%%">
                      <stop offset="0%%" stop-color="%s"/>
                      <stop offset="100%%" stop-color="%s"/>
                    </linearGradient>
                  </defs>
                  <rect x="4" y="6" width="46" height="48" rx="12" fill="url(#g)"/>
                  <rect x="10" y="12" width="34" height="36" rx="8" fill="none" stroke="rgba(255,255,255,0.3)" stroke-width="1.5"/>
                  <text x="27" y="33" font-family="Arial,sans-serif" font-size="18" font-weight="bold" fill="white" text-anchor="middle" dominant-baseline="middle">%s</text>
                  <text x="60" y="38" font-family="Arial,sans-serif" font-size="24" font-weight="bold" fill="%s" dominant-baseline="middle">%s</text>
                </svg>
                """, primary, darker, firstChar, primary, escapeXml(brandName));
    }

    /**
     * 几何图形 Logo：六边形 + 装饰点
     */
    private String generateGeometricSvg(String brandName, String primary, String darker) {
        String firstChar = escapeXml(brandName.substring(0, 1));
        return String.format("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 60" width="200" height="60">
                  <defs>
                    <linearGradient id="g" x1="0%%" y1="0%%" x2="100%%" y2="100%%">
                      <stop offset="0%%" stop-color="%s"/>
                      <stop offset="100%%" stop-color="%s"/>
                    </linearGradient>
                    <filter id="shadow">
                      <feDropShadow dx="1" dy="2" stdDeviation="2" flood-opacity="0.3"/>
                    </filter>
                  </defs>
                  <polygon points="27,6 45,16 45,36 27,46 9,36 9,16" fill="url(#g)" filter="url(#shadow)"/>
                  <polygon points="27,10 41,18 41,34 27,42 13,34 13,18" fill="none" stroke="rgba(255,255,255,0.25)" stroke-width="1"/>
                  <text x="27" y="31" font-family="Arial,sans-serif" font-size="16" font-weight="bold" fill="white" text-anchor="middle" dominant-baseline="middle">%s</text>
                  <text x="58" y="38" font-family="Arial,sans-serif" font-size="24" font-weight="bold" fill="%s" dominant-baseline="middle">%s</text>
                </svg>
                """, primary, darker, firstChar, primary, escapeXml(brandName));
    }

    /**
     * 圆润亲和 Logo：重叠圆 + 首字母
     */
    private String generateRoundedSvg(String brandName, String primary, String darker) {
        String firstChar = escapeXml(brandName.substring(0, 1));
        return String.format("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 60" width="200" height="60">
                  <defs>
                    <linearGradient id="g" x1="0%%" y1="0%%" x2="100%%" y2="100%%">
                      <stop offset="0%%" stop-color="%s"/>
                      <stop offset="100%%" stop-color="%s"/>
                    </linearGradient>
                  </defs>
                  <circle cx="18" cy="30" r="14" fill="url(#g)" opacity="0.4"/>
                  <circle cx="34" cy="30" r="14" fill="url(#g)" opacity="0.6"/>
                  <circle cx="26" cy="30" r="18" fill="url(#g)"/>
                  <text x="26" y="34" font-family="Arial,sans-serif" font-size="18" font-weight="bold" fill="white" text-anchor="middle" dominant-baseline="middle">%s</text>
                  <text x="56" y="38" font-family="Arial,sans-serif" font-size="24" font-weight="bold" fill="%s" dominant-baseline="middle">%s</text>
                </svg>
                """, darker, primary, primary, firstChar, primary, escapeXml(brandName));
    }

    /**
     * 现代感 Logo：双渐变层叠
     */
    private String generateModernSvg(String brandName, String primary, String darker) {
        String firstChar = escapeXml(brandName.substring(0, 1));
        return String.format("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 60" width="200" height="60">
                  <defs>
                    <linearGradient id="g1" x1="0%%" y1="0%%" x2="100%%" y2="100%%">
                      <stop offset="0%%" stop-color="%s"/>
                      <stop offset="100%%" stop-color="%s"/>
                    </linearGradient>
                    <linearGradient id="g2" x1="100%%" y1="0%%" x2="0%%" y2="100%%">
                      <stop offset="0%%" stop-color="%s"/>
                      <stop offset="100%%" stop-color="%s"/>
                    </linearGradient>
                  </defs>
                  <rect x="4" y="4" width="48" height="52" rx="6" fill="url(#g1)"/>
                  <rect x="4" y="4" width="36" height="44" rx="4" fill="url(#g2)" opacity="0.85"/>
                  <text x="22" y="30" font-family="Arial,sans-serif" font-size="18" font-weight="bold" fill="white" text-anchor="middle" dominant-baseline="middle">%s</text>
                  <text x="62" y="38" font-family="Arial,sans-serif" font-size="24" font-weight="bold" fill="%s" dominant-baseline="middle">%s</text>
                </svg>
                """, darker, primary, primary, darker, firstChar, primary, escapeXml(brandName));
    }

    /**
     * 经典徽章 Logo：盾牌 + 绶带
     */
    private String generateClassicSvg(String brandName, String primary, String darker) {
        String firstChar = escapeXml(brandName.substring(0, 1));
        return String.format("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 60" width="200" height="60">
                  <defs>
                    <linearGradient id="g" x1="0%%" y1="0%%" x2="0%%" y2="100%%">
                      <stop offset="0%%" stop-color="%s"/>
                      <stop offset="100%%" stop-color="%s"/>
                    </linearGradient>
                  </defs>
                  <path d="M24,4 L40,4 L48,14 L48,30 C48,38 40,46 32,50 C24,46 16,38 16,30 L16,14 Z" fill="url(#g)"/>
                  <path d="M24,4 L40,4 L48,14 L48,30 C48,38 40,46 32,50 C24,46 16,38 16,30 L16,14 Z" fill="none" stroke="rgba(255,255,255,0.2)" stroke-width="1"/>
                  <text x="32" y="34" font-family="Arial,sans-serif" font-size="20" font-weight="bold" fill="white" text-anchor="middle" dominant-baseline="middle">%s</text>
                  <text x="58" y="38" font-family="Arial,sans-serif" font-size="24" font-weight="bold" fill="%s" dominant-baseline="middle">%s</text>
                </svg>
                """, primary, darker, firstChar, primary, escapeXml(brandName));
    }

    /**
     * 科技感 Logo：菱形 + 线条装饰
     */
    private String generateTechSvg(String brandName, String primary, String darker) {
        String firstChar = escapeXml(brandName.substring(0, 1));
        return String.format("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 60" width="200" height="60">
                  <defs>
                    <linearGradient id="g" x1="0%%" y1="0%%" x2="100%%" y2="100%%">
                      <stop offset="0%%" stop-color="%s"/>
                      <stop offset="100%%" stop-color="%s"/>
                    </linearGradient>
                  </defs>
                  <rect x="4" y="8" width="44" height="44" rx="2" fill="url(#g)" transform="rotate(45, 26, 30)"/>
                  <rect x="12" y="16" width="28" height="28" rx="1" fill="none" stroke="rgba(255,255,255,0.4)" stroke-width="1.5" transform="rotate(45, 26, 30)"/>
                  <circle cx="28" cy="24" r="2" fill="white" opacity="0.6"/>
                  <circle cx="36" cy="30" r="1.5" fill="white" opacity="0.4"/>
                  <circle cx="22" cy="38" r="1" fill="white" opacity="0.3"/>
                  <text x="26" y="33" font-family="Arial,sans-serif" font-size="16" font-weight="bold" fill="white" text-anchor="middle" dominant-baseline="middle">%s</text>
                  <text x="58" y="38" font-family="Arial,sans-serif" font-size="24" font-weight="bold" fill="%s" dominant-baseline="middle">%s</text>
                </svg>
                """, primary, darker, firstChar, primary, escapeXml(brandName));
    }

    // ==================== 工具方法 ====================

    /**
     * 简单加深颜色，用于渐变辅助色
     */
    private String darkenColor(String hex) {
        if (hex == null || !hex.startsWith("#") || hex.length() < 7) {
            return "#333333";
        }
        try {
            int r = Integer.parseInt(hex.substring(1, 3), 16);
            int g = Integer.parseInt(hex.substring(3, 5), 16);
            int b = Integer.parseInt(hex.substring(5, 7), 16);
            r = Math.max(0, (int)(r * 0.6));
            g = Math.max(0, (int)(g * 0.6));
            b = Math.max(0, (int)(b * 0.6));
            return String.format("#%02X%02X%02X", r, g, b);
        } catch (Exception e) {
            return "#333333";
        }
    }

    private String escapeXml(String input) {
        if (input == null) return "";
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    @Override
    public String getToolName() {
        return "generateLogoSvg";
    }

    @Override
    public String getDisplayName() {
        return "生成Logo";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        String brandName = arguments.getStr("brandName");
        return String.format("\n\n🎨[工具调用] %s: %s\n\n", getDisplayName(), brandName);
    }
}
