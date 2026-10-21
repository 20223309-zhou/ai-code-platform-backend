package com.ai.codeplatform.ai.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;


/**
 * 图片检索服务（爬取 cn.bing.com 的异步图片接口）
 */
@Component
@Slf4j
public class SearchImageTool extends BaseTool{

    /**
     * 单次调用最多返回的图片数量。
     * 模型自己给的 count 没有上限，一次回几十个 URL 会把上下文撑爆，而页面通常只用得上 1 张。
     */
    private static final int MAX_IMAGES = 3;

    /**
     * count 缺省值
     */
    private static final int DEFAULT_IMAGES = 2;

    /**
     * 抓取超时（毫秒）。原实现没有设置超时，Bing 一慢就把整个生成流程卡住。
     */
    private static final int FETCH_TIMEOUT_MS = 5000;

    /**
     * 必应对缺少浏览器标识的请求会返回空壳页面，必须带 UA。
     */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    @Tool("搜索图片，优先使用中文关键词搜索（如\"猫\"而非\"cat\"），每次只使用单个核心关键词，不要组合多个词。"
            + "一次调用最多返回 3 张图片 URL，通常传 count=1 即可；需要多张不同图片时请换关键词多次调用")
    public String searchImage(@P("需要搜索的图片关键字") String keywords,@P("需要搜索的图片数量，取值 1~3，缺省 2") Integer count,
                              @ToolMemoryId Long appId) {
        if (StrUtil.isBlank(keywords)) {
            return null;
        }
        // 数量收敛：null 取缺省值，越界钳制到 1~MAX_IMAGES
        int wanted = (count == null) ? DEFAULT_IMAGES : Math.max(1, Math.min(count, MAX_IMAGES));
        int imgCount = 0;
        JSONArray urlList = new JSONArray();
        // 要抓取的地址（关键词必须 URL 编码，否则带空格 / 特殊字符时会拼出非法 URL）
        String fetchUrl = String.format("https://cn.bing.com/images/async?q=%s&mmasync=1",
                URLEncoder.encode(keywords, StandardCharsets.UTF_8));
        Document document;
        try {
            // 获取html文档
            document = Jsoup.connect(fetchUrl)
                    .userAgent(USER_AGENT)
                    .timeout(FETCH_TIMEOUT_MS)
                    .ignoreContentType(true)
                    .get();
        } catch (Exception e) {
            // 不抛异常：抛出去会中断整条生成链路，模型连降级（picsum 占位图）的机会都没有
            log.warn("获取图片搜索页失败，返回 null 交由模型降级。关键词: {}", keywords, e);
            return null;
        }
        // 根据文档的标签类名获取元素
        Element div = document.getElementsByClass("dgControl").first();
        if (div == null) {
            log.warn("未找到图片容器 dgControl，关键词: {}", keywords);
            return null;
        }
        // 选择所有img标签并且类名为ming的元素
        Elements imgElementList = div.select("img.mimg");
        log.info("图片计划数量：{}，图片关键词：{}", wanted, keywords);
        for (Element imgElement : imgElementList) {
            // 获取图片地址
            String fileUrl = imgElement.attr("src");
            if (StrUtil.isBlank(fileUrl)) {
                log.info("当前链接为空，已跳过: {}", fileUrl);
                continue;
            }
            // 处理图片上传地址，防止出现转义问题
            int questionMarkIndex = fileUrl.indexOf("?");
            if (questionMarkIndex > -1) {
                fileUrl = fileUrl.substring(0, questionMarkIndex);
            }
            log.info("图片关键字：{}，图片地址: {}",keywords ,fileUrl);
            urlList.add(fileUrl);
            imgCount++;
            if(imgCount >= wanted){
                break;
            }
        }
        if(urlList.isEmpty()){
            return null;
        }
        log.info("图片关键字：{}，找到的图片总数量：{}",keywords ,urlList.size());
        return urlList.toString();
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
        return String.format("\uD83D\uDDBC\uFE0F[工具调用] %s", getDisplayName());
    }
}
