package com.ai.codeplatform.rag.splitter;

import dev.langchain4j.data.segment.TextSegment;

import java.util.List;

public interface CodeSplitter {

    List<TextSegment> chunk(String filePath, String content);
}
