package com.ai.codeplatform.manager;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.ai.codeplatform.config.CosClientConfig;
import com.ai.codeplatform.exception.BusinessException;
import com.ai.codeplatform.exception.ErrorCode;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * COS对象存储管理器
 *
 */
@Component
@Slf4j
public class CosManager {

    @Resource
    private CosClientConfig cosClientConfig;

    @Resource
    private COSClient cosClient;

    /**
     * 上传对象
     *
     * @param key  唯一键
     * @param file 文件
     * @return 上传结果
     */
    public PutObjectResult putObject(String key, File file) {
        PutObjectRequest putObjectRequest = new PutObjectRequest(cosClientConfig.getBucket(), key, file);
        return cosClient.putObject(putObjectRequest);
    }

    /**
     * 上传文件到 COS 并返回访问 URL
     *
     * @param key  COS对象键（完整路径）
     * @param file 要上传的文件
     * @return 文件的访问URL，失败返回null
     */
    public String uploadFile(String key, File file) {
        // 上传文件
        PutObjectResult result = putObject(key, file);
        if (result != null) {
            // 构建访问URL
            String url = String.format("%s%s", cosClientConfig.getHost(), key);
            log.info("文件上传COS成功: {} -> {}", file.getName(), url);
            return url;
        } else {
            log.error("文件上传COS失败，返回结果为空");
            return null;
        }
    }

    /**
     * 删除 COS 对象
     * <p>
     * 异常在这里就收敛掉（记录日志 + 返回 false），不向上抛：
     * COS 的删除是幂等的，对象不存在也算成功，没必要让清理动作把业务主流程带崩。
     *
     * @param key COS 对象键
     * @return 是否删除成功
     */
    public boolean deleteObject(String key) {
        if (StrUtil.isBlank(key)) {
            return false;
        }
        try {
            cosClient.deleteObject(cosClientConfig.getBucket(), key);
            log.info("COS 对象删除成功: {}", key);
            return true;
        } catch (Exception e) {
            log.error("COS 对象删除失败: {}", key, e);
            return false;
        }
    }

    /**
     * 按访问 URL 删除 COS 对象
     * @param url 上传时返回的访问 URL
     * @return 是否删除成功
     */
    public boolean deleteObjectByUrl(String url) {
        String host = cosClientConfig.getHost();
        if (StrUtil.isBlank(url) || StrUtil.isBlank(host) || !url.startsWith(host)) {
            log.warn("跳过非本站 COS 地址的删除: {}", url);
            return false;
        }
        String key = url.substring(host.length());
        if (StrUtil.isBlank(key)) {
            return false;
        }
        return deleteObject(key);
    }

    /**
     * 上传用户图片
     */
    public String putUserImage(Long userId, MultipartFile multipartFile,String path) {
        if (multipartFile == null) {
            return null;
        }
        // 1. 校验文件大小
        long fileSize = multipartFile.getSize();
        final long ONE_M = 1024 * 1024 * 5L;
        if (fileSize > ONE_M) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件大小不能超过 5M");
        }
        // 2. 校验文件后缀
        String fileSuffix = FileUtil.getSuffix(multipartFile.getOriginalFilename());
        // 允许上传的文件后缀
        final List<String> ALLOW_FORMAT_LIST = Arrays.asList("jpeg", "jpg", "png", "webp");
        if (!ALLOW_FORMAT_LIST.contains(fileSuffix)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件类型错误");
        }
        // 3.获取源文件后缀
        String originFilename = multipartFile.getOriginalFilename();
        String suffix;
        if (!originFilename.contains(".jpg") && !originFilename.contains(".png")
                && !originFilename.contains(".jpeg") && !originFilename.contains(".webp")) {
            suffix = "png";
        } else {
            suffix = FileUtil.getSuffix(originFilename);
            if (suffix.contains("jpeg") || suffix.contains("webp")) {
                suffix = suffix.substring(0, 4);
            } else if (suffix.contains("jpg") || suffix.contains("png")) {
                suffix = suffix.substring(0, 3);
            }
        }
        // 4.拼接上传路径
        String randomString = RandomUtil.randomString(16);
        String fileName = String.format("%s_%s.%s", userId.toString(), randomString, suffix);
        String uploadPath = String.format("%s/%s", path, fileName);
        // 5.创建临时文件
        File file = null;
        PutObjectRequest putObjectRequest;
        try {
            file = File.createTempFile(uploadPath, null);
            multipartFile.transferTo(file);
            putObjectRequest = new PutObjectRequest(cosClientConfig.getBucket(), uploadPath,
                    file);
            cosClient.putObject(putObjectRequest);
        } catch (IOException e) {
            log.info("图片上传失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片上传失败");
        } finally {
            // 6.清理临时文件
            deleteTempFile(file);
        }
        return cosClientConfig.getHost() + "/" + uploadPath;
    }
    /**
     * 删除临时文件
     */
    public void deleteTempFile(File file) {
        if (file == null) {
            return;
        }
        boolean deleteResult = file.delete();
        if (!deleteResult) {
            log.error("file delete error, filepath = {}", file.getAbsolutePath());
        }
    }
}
