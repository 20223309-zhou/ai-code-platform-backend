package com.ai.codeplatform.ai.tools;

import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ai.codeplatform.config.PexelsConfig;
import com.ai.codeplatform.exception.BusinessException;
import com.ai.codeplatform.exception.ErrorCode;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.bouncycastle.util.Arrays;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.io.IOException;

import static com.ai.codeplatform.constant.PexelsConstant.*;


/**
 * Pexels 图片检索服务
 */
@Component
@Slf4j
public class SearchImageTool extends BaseTool{
    @Tool("搜索图片，优先使用中文关键词搜索（如\"猫\"而非\"cat\"），每次只使用单个核心关键词，不要组合多个词。返回多张图片URL的JSON数组供选择")
    public String searchImage(@P("需要搜索的图片关键字") String keywords,@P("需要搜索的图片数量") Integer count,
                              @ToolMemoryId Long appId) {
        int imgCount = 0;
        JSONArray urlList = new JSONArray();
        // 要抓取的地址
        String fetchUrl = String.format("https://cn.bing.com/images/async?q=%s&mmasync=1", keywords);
        Document document;
        try {
            // 获取html文档
            document = Jsoup.connect(fetchUrl).get();
        } catch (IOException e) {
            log.error("获取页面失败", e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "获取页面失败");
        }
        // 根据文档的标签类名获取元素
        Element div = document.getElementsByClass("dgControl").first();
        if (ObjUtil.isNull(div)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "获取元素失败");
        }
        // 选择所有img标签并且类名为ming的元素
        Elements imgElementList = div.select("img.mimg");
        int uploadCount = 0;
        log.info("图片计划数量：{}，图片关键词：{}", count, keywords);
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
            if(imgCount >= count){
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
        return String.format("\uD83D\uDDBB[工具调用] %s", getDisplayName());
    }
}
