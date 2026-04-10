package com.ai.codeplatform.core;

import com.ai.codeplatform.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;

import java.io.File;
import java.util.List;

@SpringBootTest
public class AiCodeGeneratorFacadeTest {
    @Resource
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;
    @Test
    void generateAndSaveCode() {
        String messagePrompt = "请生成一个登录页面，不超过20行";
        File file = aiCodeGeneratorFacade.generateAndSaveCode(messagePrompt, CodeGenTypeEnum.MULTI_FILE);

    }

    @Test
    void generateAndSaveCodeStream() {
        Flux<String> codeFlux = aiCodeGeneratorFacade.generateAndSaveCodeStream("请生成一个登录页面，不超过20行", CodeGenTypeEnum.MULTI_FILE);
        List<String> block = codeFlux.collectList().block();
        String result = String.join("", block);
    }
}
