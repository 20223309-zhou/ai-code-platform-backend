package com.ai.codeplatform.core;

import com.ai.codeplatform.utils.SpringContextUtil;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SpringBootTest
public class AiCodeGeneratorFacadeTest {

    @Test
    public void testChatModel(){
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

    @Test
    public void testAIServices() throws Exception {
        ChatModel chatModel = SpringContextUtil.getBean("routingChatModelPrototype", ChatModel.class);
        StreamingChatModel streamingChatModel = SpringContextUtil.getBean("deepseekReasoningStreamingChatModelPrototype",StreamingChatModel.class);
        ChatService chatService = AiServices.builder(ChatService.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .build();
        // start() 之后回调跑在 IO 线程上，测试方法立即返回会把输出截断，用 latch 等它结束
        CountDownLatch latch = new CountDownLatch(1);
        chatService.chat("你是谁呀？")
                .onPartialResponse(text -> System.out.print(text))
                // langchain4j 1.15 硬性校验：onError / ignoreErrors 必须且只能调用一次，
                // 否则 start() 直接抛 IllegalConfigurationException
                .onError(error -> {
                    error.printStackTrace();
                    latch.countDown();
                })
                .onCompleteResponse((ChatResponse res) -> {
                    // 服务商不返回 usage 时这里是 null，直接取会 NPE 且看不到堆栈
                    TokenUsage usage = res.tokenUsage();
                    if (usage == null) {
                        System.out.println("\n[本次响应没有 tokenUsage]");
                    } else {
                        System.out.printf("%n输入token：%d%n输出token：%d%n总token：%d%n",
                                usage.inputTokenCount(), usage.outputTokenCount(), usage.totalTokenCount());
                    }
                    latch.countDown();
                })
                .start();
        latch.await(60, TimeUnit.SECONDS);
    }

}
