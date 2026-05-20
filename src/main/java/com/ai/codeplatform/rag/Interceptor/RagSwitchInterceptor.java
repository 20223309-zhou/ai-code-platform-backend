package com.ai.codeplatform.rag.Interceptor;

import com.ai.codeplatform.rag.RagSwitchHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RagSwitchInterceptor implements HandlerInterceptor {

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler, Exception ex) {
        RagSwitchHolder.clear();
    }
}