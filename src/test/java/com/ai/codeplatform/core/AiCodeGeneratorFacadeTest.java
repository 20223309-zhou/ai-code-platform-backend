package com.ai.codeplatform.core;

import com.ai.codeplatform.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;

import java.io.File;
import java.util.List;

@SpringBootTest
public class AiCodeGeneratorFacadeTest {
    @Resource
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;
//    @Test
//    void generateAndSaveCode() {
//        String messagePrompt = "请生成一个登录页面，不超过20行";
//        File file = aiCodeGeneratorFacade.generateAndSaveCode(messagePrompt, CodeGenTypeEnum.MULTI_FILE);
//
//    }
//
//    @Test
//    void generateAndSaveCodeStream() {
//        Flux<String> codeFlux = aiCodeGeneratorFacade.generateAndSaveCodeStream("请生成一个登录页面，不超过20行", CodeGenTypeEnum.MULTI_FILE);
//        List<String> block = codeFlux.collectList().block();
//        String result = String.join("", block);
//    }

    @Test
    void generateVueProjectCodeStream() {
        Flux<String> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream(
                "简单的任务记录网站，总代码量不超过 200 行",
                CodeGenTypeEnum.VUE_PROJECT, 1L);
        // 阻塞等待所有数据收集完成
        List<String> result = codeStream.collectList().block();
        // 验证结果
        Assertions.assertNotNull(result);
        String completeContent = String.join("", result);
        Assertions.assertNotNull(completeContent);
    }

}
