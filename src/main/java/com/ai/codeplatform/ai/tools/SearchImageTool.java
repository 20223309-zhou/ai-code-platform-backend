package com.ai.codeplatform.ai.tools;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ai.codeplatform.config.PexelsConfig;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;

import static com.ai.codeplatform.constant.PexelsConstant.*;


/**
 * Pexels 图片检索服务
 */
@Component
@Slf4j
public class SearchImageTool extends BaseTool{

    @Resource
    private PexelsConfig pexelsConfig;

    private final OkHttpClient httpClient = new OkHttpClient();

    @Tool("搜索图片，优先使用英文关键词搜索（如\"cat\"而非\"猫\"），每次只使用单个核心关键词，不要组合多个词。返回多张图片URL的JSON数组供选择")
    public String searchImage(@P("需要搜索的图片关键字") String keywords,@ToolMemoryId Long appId) {
        try {
            String url = buildSearchUrl(keywords);
            
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", pexelsConfig.getApiKey())
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Pexels API 调用失败: {}", response.code());
                    return null;
                }

                String responseBody = response.body().string();
                return extractImageUrl(responseBody, keywords);
            }
        } catch (IOException e) {
            log.error("Pexels API 调用异常", e);
            return null;
        }
    }

    /**
     * 构建搜索 URL
     *
     * @param keywords 搜索关键词
     * @return 完整的搜索 URL
     */
    private String buildSearchUrl(String keywords) {
        return String.format("%s?query=%s&per_page=%d&orientation=%s",
                PEXELS_API_URL,
                keywords,
                PEXELS_PER_PAGE,
                PEXELS_ORIENTATION_LANDSCAPE);
    }

    /**
     * 从响应中提取图片 URL
     *
     * @param responseBody 响应体
     * @param keywords     搜索关键词（用于日志）
     * @return 图片 URL，未找到返回 null
     */
    private String extractImageUrl(String responseBody, String keywords) {
        // 解析响应体
        JSONObject jsonObject = JSONUtil.parseObj(responseBody);
        JSONArray photos = jsonObject.getJSONArray("photos");

        if (photos.isEmpty()) {
            log.warn("Pexels 未检索到图片: {}", keywords);
            return null;
        }

        // 收集所有图片 URL，返回 JSON 数组字符串方便 AI 理解
        JSONArray urlList = new JSONArray();
        for (int i = 0; i < photos.size(); i++) {
            JSONObject photo = photos.getJSONObject(i);
            JSONObject src = photo.getJSONObject("src");
            String url = src.getStr("large");
            if (url == null || url.isEmpty()) {
                url = src.getStr("medium");
            }
            if (url == null || url.isEmpty()) {
                url = src.getStr("original");
            }
            if (url != null && !url.isEmpty()) {
                urlList.add(url);
            }
        }

        if (urlList.isEmpty()) {
            log.warn("Pexels 图片 URL 为空: {}", keywords);
            return null;
        }

        log.info("Pexels 检索到 {} 张图片, 关键词: {}", urlList.size(), keywords);
        urlList.forEach(url -> log.info("  - {}", url));
        return urlList.toString(); // 返回 JSON 数组: ["url1","url2",...]
    }

    @Override
    public String getToolName() {
        return "searchImage";
    }

    @Override
    public String getDisplayName() {
        return "搜索图片";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        return String.format("\uD83D\uDDBB[工具调用] %s", getDisplayName());
    }
}
