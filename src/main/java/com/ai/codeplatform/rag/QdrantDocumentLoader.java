package com.ai.codeplatform.rag;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.ai.codeplatform.exception.BusinessException;
import com.ai.codeplatform.exception.ErrorCode;
import com.ai.codeplatform.rag.config.QdrantConfig;
import com.ai.codeplatform.rag.splitter.SplitExecutor;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15.BgeSmallZhV15EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
public class QdrantDocumentLoader {

    @Resource
    private EmbeddingStore<TextSegment> embeddingStore;

    @Resource
    private BgeSmallZhV15EmbeddingModel embeddingModel;

    @Resource
    private SplitExecutor splitExecutor;

    @Resource
    private QdrantClient qdrantClient;

    @Resource
    private QdrantConfig qdrantConfig;

    /**
     * 加载文档
     */
    public void loadDocuments(String dirPath) {
        log.info("开始智能加载文档到 Qdrant...");
        try {
            // 从Qdrant中获取已存在的文件名
            Set<String> existingPaths = getExistingFilePaths();
            log.info("Qdrant 中已有 {} 个文件", existingPaths.size());
            
            int addedCount = 0;
            int skippedCount = 0;

            // 需要写入语料库的文件相对路径
            List<Path> files = Files.walk(Paths.get(dirPath))
                    .filter(p -> {
                        String path = p.toString();
                        return !path.contains("node_modules")
                                && !path.contains("dist")
                                && !path.contains(".git");
                    })
                    // 只保留普通文件，排除目录
                    .filter(path1 -> Files.isRegularFile(path1))
                    // 只保留扩展名为"vue", "html", "js", "css"的文件
                    .filter(p -> isSupportedFile(p.toString()))
                    .toList();
            
            log.info("扫描到 {} 个待处理文件", files.size());
            // 遍历指定目录所有筛选过的文件
            for (var file : files) {
                // 文件的绝对路径
                String filePath = file.toString();
                String content = FileUtil.readUtf8String(file.toFile());
                log.info("处理文件: {}, 内容长度: {}", FileUtil.getName(filePath), content.length());
                if (content.isEmpty()) {
                    log.warn("文件内容为空，跳过: {}", filePath);
                    continue;
                }
                if (!existingPaths.contains(filePath)) {
                    List<TextSegment> segments = splitExecutor.chunk(filePath, content).stream()
                            .filter(s -> s != null && StrUtil.isNotBlank(s.text()))
                            .toList();

                    List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
                    embeddingStore.addAll(embeddings, segments);
                    log.info("新增文件: {}", FileUtil.getName(filePath));
                    addedCount++;
                }else{
                    log.info("已存在文件: {}", FileUtil.getName(filePath));
                    skippedCount++;
                }
            }
            log.info("加载完成 - 新增: {}, 跳过: {}",
                    addedCount, skippedCount);
            
        } catch (Exception e) {
            log.error("加载文档失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "加载文档失败: " + e.getMessage());
        }
    }

    /**
     * 获取 Qdrant 中已存在的文件路径
     *
     * 注意：之前用“零向量 search + maxResults(10000)”枚举全量点，但 search 默认会回传每条命中的
     * 512 维向量，1 万条 ≈ 20MB，超过 gRPC 默认 4MB 单条消息上限，导致
     * RESOURCE_EXHAUSTED: Decompressed gRPC message exceeds maximum size 4194304。
     * 改用 Qdrant 的 scroll 接口：专为遍历全量点设计，支持分页，且 withVector=false 不返回向量，
     * 响应体极小，从根本上规避 4MB 限制。
     */
    private Set<String> getExistingFilePaths() {
        try {
            Set<String> paths = new LinkedHashSet<>();
            int limit = 256;
            Common.PointId offset = null;
            do {
                Points.ScrollPoints.Builder scrollBuilder = Points.ScrollPoints.newBuilder()
                        .setCollectionName(qdrantConfig.getCollectionName())
                        .setLimit(limit)
                        .setWithVectors(Points.WithVectorsSelector.newBuilder().setEnable(false).build())
                        .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build());
                if (offset != null) {
                    scrollBuilder.setOffset(offset);
                }

                Points.ScrollResponse response = qdrantClient.scrollAsync(scrollBuilder.build()).get();
                List<Points.RetrievedPoint> points = response.getResultList();
                if (points.isEmpty()) {
                    break;
                }

                for (Points.RetrievedPoint p : points) {
                    JsonWithInt.Value v = p.getPayloadMap().get("file_name");
                    if (v != null) {
                        String fileName = v.getStringValue();
                        if (fileName != null && !fileName.isEmpty()) {
                            paths.add(fileName);
                        }
                    }
                }

                // 下一页游标 = 本页最后一条记录的 id
                Common.PointId lastId = points.get(points.size() - 1).getId();
                if (lastId.getUuid() != null && !lastId.getUuid().isEmpty()) {
                    offset = Common.PointId.newBuilder().setUuid(lastId.getUuid()).build();
                } else {
                    offset = Common.PointId.newBuilder().setNum(lastId.getNum()).build();
                }

                // 本页不足 limit 条，说明已是最后一页
                if (points.size() < limit) {
                    break;
                }
            } while (true);

            return paths;
        } catch (Exception e) {
            log.warn("获取现有文件路径失败", e);
            return Set.of();
        }
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String path) {
        int dotIndex = path.lastIndexOf(".");
        return dotIndex > 0 ? path.substring(dotIndex + 1) : "";
    }

    /**
     * 判断文件是否支持
     */
    private boolean isSupportedFile(String path) {
        String ext = getFileExtension(path).toLowerCase();
        return Set.of("vue", "html", "js", "css").contains(ext);
    }
}
