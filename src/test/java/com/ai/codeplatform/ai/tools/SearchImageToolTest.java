package com.ai.codeplatform.ai.tools;


import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class SearchImageToolTest {
    @Resource
    private SearchImageTool searchImageTool;
    @Test
    void searchImage() {
        String url = searchImageTool.searchImage("初音未来",2, 1L);
        System.out.println("搜索结果：" + url);
    }
}
