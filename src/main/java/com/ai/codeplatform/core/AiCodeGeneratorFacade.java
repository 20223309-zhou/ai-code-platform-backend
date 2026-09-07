package com.ai.codeplatform.core;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
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
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.File;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

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
        // 把图片注入记忆：langchain4j 对 @UserMessage UserMessage 参数会序列化为 toString() 导致图片丢失，
        // 因此图片改由记忆(ChatMemory)传入，文本仍通过 @UserMessage String 参数传入
        aiCodeGeneratorServiceFactory.addUserImagesToMemory(appId, codeGenTypeEnum, userMessage);
        String userText = toText(userMessage);
        return switch (codeGenTypeEnum) {
            case HTML -> {
                // 预创建目录，确保修改场景下工具能定位到项目目录
                String htmlPath = AppConstant.CODE_OUTPUT_ROOT_DIR + "/html_" + appId;
                FileUtil.mkdir(htmlPath);
                TokenStream codeStream = aiCodeGeneratorService.generateHtmlCodeStream(appId, userText);
                yield processTokenStream(codeStream, CodeGenTypeEnum.HTML, appId);
            }
            case MULTI_FILE -> {
                // 先创建项目目录，确保工具调用时目录已存在
                String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + "/multi_file_" + appId;
                FileUtil.mkdir(projectPath);
                TokenStream codeStream = aiCodeGeneratorService.generateMultiFileCodeStream(appId, userText);
                yield processTokenStream(codeStream, CodeGenTypeEnum.MULTI_FILE, appId);
            }
            case VUE_PROJECT -> {
                // 先创建项目目录，确保工具调用时目录已存在
                String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + "/vue_project_" + appId;
                FileUtil.mkdir(projectPath);
                TokenStream codeStream = aiCodeGeneratorService.generateVueProjectCodeStream(appId, userText);
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
                    .onPartialResponseWithContext((PartialResponse partialResponse, PartialResponseContext context) -> {
                        // 检查是否已取消生成，若取消则终止流
                        if (cancelGenerationManager.isCancelled(appId)) {
                            context.streamingHandle().cancel();
                            log.info("生成阶段，用户取消生成，取消任务：{}", appId);
                            cancelGenerationManager.remove(appId);
                            sink.complete();
                            return;
                        }
                        // 封装AI响应消息并发送到流中
                        AiResponseMessage aiResponseMessage = new AiResponseMessage(partialResponse.text());
                        sink.next(JSONUtil.toJsonStr(aiResponseMessage));
                    })
                    .onPartialThinkingWithContext((PartialThinking partialThinking,PartialThinkingContext context) -> {
                        if (cancelGenerationManager.isCancelled(appId)) {
                            context.streamingHandle().cancel();
                            log.info("思考阶段，用户取消生成，取消任务：{}", appId);
                            cancelGenerationManager.remove(appId);
                            sink.complete();
                            return;
                        }
                        Map<String, String> thinkingMsg = Map.of(
                            "type", StreamMessageTypeEnum.THINKING.getValue(),
                            "data", partialThinking.text()
                        );
                        sink.next(JSONUtil.toJsonStr(thinkingMsg));
                    })
                    .onPartialToolCallWithContext((PartialToolCall partialToolCall, PartialToolCallContext context) -> {
                        if (cancelGenerationManager.isCancelled(appId)) {
                            context.streamingHandle().cancel();
                            cancelGenerationManager.remove(appId);
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
                        // 同步构建vue项目
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
     * 通用流式代码处理方法（HTML/MULTI 使用 tokenStream）
     * @param tokenStream 代码流
     * @param codeGenType 代码生成类型
     * @param appId       应用 ID
     * @return 流式响应
     */
    private Flux<String> processTokenStream(TokenStream tokenStream, CodeGenTypeEnum codeGenType, Long appId) {
        StringBuilder codeBuilder = new StringBuilder();
        AtomicBoolean writeToolInvoked = new AtomicBoolean(false);
        return Flux.create(sink -> {
            tokenStream
                    // 处理AI响应的信息
                    .onPartialResponseWithContext((PartialResponse partialResponse, PartialResponseContext context) -> {
                        // 检查用户是否已取消生成，若取消则终止流
                        if (cancelGenerationManager.isCancelled(appId)) {
                            context.streamingHandle().cancel();
                            log.info("生成阶段，用户取消生成，取消任务：{}", appId);
                            cancelGenerationManager.remove(appId);
                            sink.complete();
                            return;
                        }
                        codeBuilder.append(partialResponse.text());
                        // 封装AI响应消息并发送到流中
                        AiResponseMessage aiResponseMessage = new AiResponseMessage(partialResponse.text());
                        // 转为json字符串继续交给下游处理
                        sink.next(JSONUtil.toJsonStr(aiResponseMessage));
                    })
                    // 处理AI的思考信息
                    .onPartialThinkingWithContext((PartialThinking partialThinking,PartialThinkingContext context) -> {
                        // 检查用户是否已取消生成，若取消则终止流
                        if (cancelGenerationManager.isCancelled(appId)) {
                            context.streamingHandle().cancel();
                            log.info("思考阶段，用户取消生成，取消任务：{}", appId);
                            cancelGenerationManager.remove(appId);
                            sink.complete();
                            return;
                        }
                        // 将AI思考过程封装为map后，转为json字符串给下游处理
                        Map<String, String> thinkingMsg = Map.of(
                                "type", StreamMessageTypeEnum.THINKING.getValue(),
                                "data", partialThinking.text()
                        );
                        sink.next(JSONUtil.toJsonStr(thinkingMsg));
                    })
                    // 处理工具调用信息，标记写文件工具被调用
                    .onPartialToolCallWithContext((PartialToolCall partialToolCall, PartialToolCallContext context) -> {
                        if (cancelGenerationManager.isCancelled(appId)) {
                            context.streamingHandle().cancel();
                            cancelGenerationManager.remove(appId);
                            sink.complete();
                            return;
                        }
                        // 只有写文件的工具才标记跳过保存，只读工具（WebFetch/FileRead）不阻塞
                        String toolName = partialToolCall.name();
                        if ("modifyFile".equals(toolName) || "writeFile".equals(toolName) || "deleteFile".equals(toolName)) {
                            writeToolInvoked.set(true);
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
                        sink.complete();
                        // 写文件工具已被调用时（修改场景），文件已被工具直接写盘，不需要解析保存
                        // 只读工具（WebFetchTool/FileReadTool）不阻塞正常保存
                        if (writeToolInvoked.get()) {
                            return;
                        }
                        try {
                            String completeCode = codeBuilder.toString();
                            if (StrUtil.isNotBlank(completeCode)){
                                Object parsedResult = CodeParserExecutor.executeParser(completeCode, codeGenType);
                                File savedDir = CodeFileSaverExecutor.executeSaver(parsedResult, codeGenType, appId);
                                log.info("保存成功，路径为：" + savedDir.getAbsolutePath());
                            }
                        } catch (Exception e) {
                            log.error("保存失败: {}", e.getMessage());
                        }
                    })
                    .onError((Throwable error) -> {
                        cancelGenerationManager.remove(appId);
                        error.printStackTrace();
                        sink.error(error);
                    })
                    .start();
        });
    }

//    /**
//     * 通用流式代码处理方法（使用 appId）
//     *
//     * @param codeStream  代码流
//     * @param codeGenType 代码生成类型
//     * @param appId       应用 ID
//     * @return 流式响应
//     */
//    private Flux<String> processCodeStream(Flux<String> codeStream, CodeGenTypeEnum codeGenType, Long appId) {
//        StringBuilder codeBuilder = new StringBuilder();
//        return codeStream
//                .takeWhile(chunk -> !cancelGenerationManager.isCancelled(appId))
//                .doOnNext(chunk -> {
//                    codeBuilder.append(chunk);
//                })
//                .doOnComplete(() -> {
//                    cancelGenerationManager.remove(appId);
//                    if (cancelGenerationManager.isCancelled(appId)) {
//                        return;
//                    }
//                    try {
//                        String completeCode = codeBuilder.toString();
//                        Object parsedResult = CodeParserExecutor.executeParser(completeCode, codeGenType);
//                        File savedDir = CodeFileSaverExecutor.executeSaver(parsedResult, codeGenType, appId);
//                        log.info("保存成功，路径为：" + savedDir.getAbsolutePath());
//                    } catch (Exception e) {
//                        log.error("保存失败: {}", e.getMessage());
//                    }
//                })
//                .doOnCancel(() -> cancelGenerationManager.remove(appId))
//                .doOnError(e -> cancelGenerationManager.remove(appId));
//    }

    /**
     * 从多模态 UserMessage 中提取纯文本内容（忽略图片等二进制内容），
     * 用于作为 {@code @UserMessage String} 参数传给 AI 服务。
     * <p>
     * 注意：不能使用 {@code UserMessage.singleText()}，该方法在含图片的多模态消息上会抛出异常。
     * 图片由 {@link AiCodeGeneratorServiceFactory#addUserImagesToMemory} 单独注入 ChatMemory。
     *
     * @param userMessage 包含文本和图片的用户消息
     * @return 拼接后的纯文本
     */
    private String toText(UserMessage userMessage) {
        return userMessage.contents().stream()
                .filter(content -> content instanceof TextContent)
                .map(content -> ((TextContent) content).text())
                .collect(Collectors.joining("\n"));
    }

}
