package com.ai.codeplatform.core;

import com.ai.codeplatform.utils.SpringContextUtil;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class AiCodeGeneratorFacadeTest {

    @Test
    public void testAIServices(){
        ChatModel chatModel = SpringContextUtil.getBean("routingChatModelPrototype", ChatModel.class);
        ChatResponse chat = chatModel.chat(UserMessage.from("你是谁呀？"));

        String text = chat.aiMessage().text();
        AiMessage aiMessage = chat.aiMessage();
        ChatResponseMetadata metadata = chat.metadata();

        String modelName = chat.metadata().modelName();
        Integer inputTokenCount = chat.metadata().tokenUsage().inputTokenCount();
        Integer outputTokenCount = chat.metadata().tokenUsage().outputTokenCount();
        String finish = chat.metadata().finishReason().toString();

        System.out.println("AI回复：" + text);
        System.out.println("模型名称："+modelName);
        System.out.println("输入token："+inputTokenCount);
        System.out.println("输出token："+outputTokenCount);
        System.out.println("结束原因："+finish);
    }

}
