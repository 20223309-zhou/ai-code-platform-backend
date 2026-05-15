package com.ai.codeplatform.core;

import cn.hutool.json.JSONUtil;
import com.ai.codeplatform.ai.AiCodeGeneratorService;
import com.ai.codeplatform.ai.AiCodeGeneratorServiceFactory;
import com.ai.codeplatform.ai.model.HtmlCodeResult;
import com.ai.codeplatform.ai.model.MultiFileCodeResult;
import com.ai.codeplatform.ai.model.message.AiResponseMessage;
import com.ai.codeplatform.ai.model.message.ToolExecutedMessage;
import com.ai.codeplatform.ai.model.message.ToolRequestMessage;
import com.ai.codeplatform.ai.model.message.StreamMessageTypeEnum;
import com.ai.codeplatform.constant.AppConstant;
import com.ai.codeplatform.core.builder.VueProjectBuilder;
import com.ai.codeplatform.core.parser.CodeParserExecutor;
import com.ai.codeplatform.core.saver.CodeFileSaverExecutor;
import com.ai.codeplatform.exception.BusinessException;
import com.ai.codeplatform.exception.ErrorCode;
import com.ai.codeplatform.manager.CancelGenerationManager;
import com.ai.codeplatform.model.enums.CodeGenTypeEnum;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.File;
import java.util.Map;

/**
 * AI 代码生成外观类，组合生成和保存功能
 */
@Service
@Slf4j
public class AiCodeGeneratorFacade {

    @Resource
    private AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

    @Resource
    private VueProjectBuilder vueProjectBuilder;

    @Resource
    private CancelGenerationManager cancelGenerationManager;
    /**
     * 统一入口：根据类型生成并保存代码（使用 appId）
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum 生成类型
     * @return 保存的目录
     */
    public File generateAndSaveCode(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }
        // 根据 appId 获取服务实例
        AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId);
        return switch (codeGenTypeEnum) {
            case HTML -> {
                HtmlCodeResult result = aiCodeGeneratorService.generateHtmlCode(userMessage);
                yield CodeFileSaverExecutor.executeSaver(result, CodeGenTypeEnum.HTML, appId);
            }
            case MULTI_FILE -> {
                MultiFileCodeResult result = aiCodeGeneratorService.generateMultiFileCode(userMessage);
                yield CodeFileSaverExecutor.executeSaver(result, CodeGenTypeEnum.MULTI_FILE, appId);
            }
            default -> {
                String errorMessage = "不支持的生成类型：" + codeGenTypeEnum.getValue();
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, errorMessage);
            }
        };
    }

    /**
     * 统一入口：根据类型生成并保存代码（流式，使用 appId）
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum 生成类型
     * @param appId           应用 ID
     */
    public Flux<String> generateAndSaveCodeStream(UserMessage userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }
        // 根据 appId 获取对应的 AI 服务实例
        AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum);
        return switch (codeGenTypeEnum) {
            case HTML -> {
                Flux<String> codeStream = aiCodeGeneratorService.generateHtmlCodeStream(userMessage);
                yield processCodeStream(codeStream, CodeGenTypeEnum.HTML, appId);
            }
            case MULTI_FILE -> {
                Flux<String> codeStream = aiCodeGeneratorService.generateMultiFileCodeStream(userMessage);
                yield processCodeStream(codeStream, CodeGenTypeEnum.MULTI_FILE, appId);
            }
            case VUE_PROJECT -> {
                TokenStream codeStream = aiCodeGeneratorService.generateVueProjectCodeStream(appId, userMessage);
                yield processTokenStream(codeStream, appId);
            }
            default -> {
                String errorMessage = "不支持的生成类型：" + codeGenTypeEnum.getValue();
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, errorMessage);
            }
        };
    }

    /**
     * 将 TokenStream 转换为 Flux<String>，并传递工具调用信息
     * @param tokenStream TokenStream 对象
     * @return Flux<String> 流式响应
     */
    private Flux<String> processTokenStream(TokenStream tokenStream, Long appId) {
        return Flux.create(sink -> {
            tokenStream
                    .onPartialResponse((String partialResponse) -> {
                        // 检查是否已取消生成，若取消则终止流
                        if (cancelGenerationManager.isCancelled(appId)) {
                            sink.complete();
                            return;
                        }
                        // 封装AI响应消息并发送到流中
                        AiResponseMessage aiResponseMessage = new AiResponseMessage(partialResponse);
                        sink.next(JSONUtil.toJsonStr(aiResponseMessage));
                    })
                    .onPartialThinking((PartialThinking partialThinking) -> {
                        if (cancelGenerationManager.isCancelled(appId)) {
                            sink.complete();
                            return;
                        }
                        Map<String, String> thinkingMsg = Map.of(
                            "type", StreamMessageTypeEnum.THINKING.getValue(),
                            "data", partialThinking.text()
                        );
                        sink.next(JSONUtil.toJsonStr(thinkingMsg));
                    })
                    .onPartialToolCall((PartialToolCall partialToolCall) -> {
                        if (cancelGenerationManager.isCancelled(appId)) {
                            sink.complete();
                            return;
                        }
                        ToolExecutionRequest toolExecutionRequest = ToolExecutionRequest.builder()
                                .id(partialToolCall.id())
                                .name(partialToolCall.name())
                                .arguments(partialToolCall.partialArguments())
                                .build();
                        ToolRequestMessage toolRequestMessage = new ToolRequestMessage(toolExecutionRequest);
                        sink.next(JSONUtil.toJsonStr(toolRequestMessage));
                    })
                    // 处理工具执行完成后的结果
                    .onToolExecuted((ToolExecution toolExecution) -> {
                        if (cancelGenerationManager.isCancelled(appId)) {
                            sink.complete();
                            return;
                        }
                        ToolExecutedMessage toolExecutedMessage = new ToolExecutedMessage(toolExecution);
                        sink.next(JSONUtil.toJsonStr(toolExecutedMessage));
                    })
                    .onCompleteResponse((ChatResponse response) -> {
                        if (cancelGenerationManager.isCancelled(appId)) {
                            cancelGenerationManager.remove(appId);
                            sink.complete();
                            return;
                        }
                        cancelGenerationManager.remove(appId);
                        String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + "/vue_project_" + appId;
                        vueProjectBuilder.buildProject(projectPath);
                        sink.complete();
                    })
                    .onError((Throwable error) -> {
                        cancelGenerationManager.remove(appId);
                        error.printStackTrace();
                        sink.error(error);
                    })
                    .start();

        });
    }


    /**
     * 通用流式代码处理方法（使用 appId）
     *
     * @param codeStream  代码流
     * @param codeGenType 代码生成类型
     * @param appId       应用 ID
     * @return 流式响应
     */
    private Flux<String> processCodeStream(Flux<String> codeStream, CodeGenTypeEnum codeGenType, Long appId) {
        StringBuilder codeBuilder = new StringBuilder();
        return codeStream
                .takeWhile(chunk -> !cancelGenerationManager.isCancelled(appId))
                .doOnNext(chunk -> {
                    codeBuilder.append(chunk);
                })
                .doOnComplete(() -> {
                    cancelGenerationManager.remove(appId);
                    if (cancelGenerationManager.isCancelled(appId)) {
                        return;
                    }
                    try {
                        String completeCode = codeBuilder.toString();
                        Object parsedResult = CodeParserExecutor.executeParser(completeCode, codeGenType);
                        File savedDir = CodeFileSaverExecutor.executeSaver(parsedResult, codeGenType, appId);
                        log.info("保存成功，路径为：" + savedDir.getAbsolutePath());
                    } catch (Exception e) {
                        log.error("保存失败: {}", e.getMessage());
                    }
                })
                .doOnCancel(() -> cancelGenerationManager.remove(appId))
                .doOnError(e -> cancelGenerationManager.remove(appId));
    }

}
