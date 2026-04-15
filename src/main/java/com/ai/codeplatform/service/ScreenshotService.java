package com.ai.codeplatform.service;

public interface ScreenshotService {
    /**
     * 生成应用截图并上传
     *
     * @param webUrl 应用访问地址
     * @return 截图URL
     */
    String generateAndUploadScreenshot(String webUrl);
}
