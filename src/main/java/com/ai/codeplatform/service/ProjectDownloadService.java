package com.ai.codeplatform.service;

import jakarta.servlet.http.HttpServletResponse;

public interface ProjectDownloadService {
    /**
     * 将项目目录下载为 ZIP 文件
     *
     * @param projectPath           项目目录路径
     * @param downloadFileName      下载文件名
     * @param response              HTTP 响应
     */
    void downloadProjectAsZip(String projectPath, String downloadFileName, HttpServletResponse response);
}
