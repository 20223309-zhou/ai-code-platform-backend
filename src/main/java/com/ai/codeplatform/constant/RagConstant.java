package com.ai.codeplatform.constant;

import java.io.File;

public interface RagConstant {

    // 需要加载到内存中的文件目录
    String RAG_LOAD_DIRECTORY_PATH =
            System.getProperty("user.dir") + File.separator + "tmp/code_output_vetted";

    String RAG_Embedding_PATH =
            System.getProperty("user.dir") + File.separator + "src/main/resources/embedding/embeddings.json";
}
