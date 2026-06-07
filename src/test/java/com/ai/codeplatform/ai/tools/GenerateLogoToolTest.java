package com.ai.codeplatform.ai.tools;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class GenerateLogoToolTest {
    @Resource
    GenerateLogoTool generateLogoTool;
    @Test
    void generateLogoSvg() {
        String s = generateLogoTool.generateLogoSvg("iPanda", "#4F7CFF","doraemon");
        System.out.println("SVG代码："+s);
    }
}
