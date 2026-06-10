package com.ai.codeplatform.rag;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.ai.codeplatform.exception.BusinessException;
import com.ai.codeplatform.exception.ErrorCode;
import com.ai.codeplatform.rag.splitter.SplitExecutor;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15.BgeSmallZhV15EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
     */
    private Set<String> getExistingFilePaths() {
        try {
            // 用零向量搜索全部已有文件（BgeSmallZhV15 维度为 512）
            float[] zeroVector = new float[512];
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(Embedding.from(zeroVector))
                    .maxResults(10000)
                    .minScore(0.0)
                    .build();

            EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);
            //result.matches() 返回的是 List<EmbeddingMatch<TextSegment>>，其中每个 EmbeddingMatch 包含：
            //1.embedded() - 嵌入的文本片段（TextSegment），包含：
            //  文本内容
            //  元数据（metadata），如文件名、来源等
            //2.score() - 相似度分数（0-1之间）
            //3.embeddingId() - 向量ID
            //4.embedding() - 向量本身
            return result.matches().stream()
                    .map(EmbeddingMatch -> {
                        TextSegment embedded = EmbeddingMatch.embedded();
                        return embedded;
                    })
                    .map(textSegment -> {
                        return textSegment.metadata();
                    })
                    .map(metadata -> metadata.getString("file_name"))
                    .filter(path -> path != null && !path.isEmpty())
                    .collect(Collectors.toSet());

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
