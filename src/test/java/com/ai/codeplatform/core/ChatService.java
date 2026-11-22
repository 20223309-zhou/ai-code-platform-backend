package com.ai.codeplatform.core;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

public interface ChatService {
    @SystemMessage("你是一个贴心的AI助手，善于回答用户问题，并温柔的给出建议。")
    TokenStream chat(@UserMessage String userMessage);

}
