# iCodeAI — AI 驱动的应用生成平台
![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.4-6DB33F?logo=springboot)
![LangChain4j](https://img.shields.io/badge/LangChain4j-1.15-000000?logo=langchain)
![MyBatis-Flex](https://img.shields.io/badge/MyBatis_Flex-1.11-FF6A00?logo=mybatis)
![Qdrant](https://img.shields.io/badge/Qdrant-1.17-EE3664?logo=qdrant)
![MySQL](https://img.shields.io/badge/MySQL-8-4479A1?logo=mysql)
![Redis](https://img.shields.io/badge/Redis-7.2-DC382D?logo=redis)
![Vue.js](https://img.shields.io/badge/Vue.js-3.5-4FC08D?logo=vuedotjs)
![TypeScript](https://img.shields.io/badge/TypeScript-5.8-3178C6?logo=typescript)


iCodeAI  是一个基于 **Langchain4j + RAG + Qdrant + Spring Boot + Vue 3 + 大模型能力** 构建的智能应用生成平台。

<img width="2560" height="1199" alt="image" src="https://github.com/user-attachments/assets/9ef2989b-b1b6-4edd-94ee-6b65e83365e3" />


用户可以通过自然语言描述需求，快速生成网页应用原型，并继续通过对话方式迭代修改。平台同时支持上传图片/文本文件作为参考资料、预览生成结果、下载源码、部署应用、模板复用以及后台管理等功能。

本仓库包含两个子项目：

- `ai-code-platform-backend`：后端服务
- `ai-code-platform-frontend`：前端界面 https://github.com/20223309-zhou/ai-code-platform-frontend

---

## 项目特色

- 自然语言驱动的应用生成
- 支持多模态识别，支持图片、文本文件作为生成参考
- 支持 **RAG 知识库检索**——已部署的模板代码作为参考语料，提升生成一致性和成功率
- 支持 **Function Calling 工具调用**——AI 自动调用文件读写、网页获取等工具，实现更智能的代码生成
- 支持流式返回 AI 生成过程
- 支持多轮对话式迭代修改
- 支持停止生成任务（源头截断 AI 输出）
- 支持应用预览、代码下载、应用部署
- 支持模板广场与模板复用
- 支持管理员后台管理用户、应用、对话、日志和统计
- 支持 VIP 会员体系——等级管理 + 使用额度控制

---

## 主要功能

### 用户侧功能

- 用户注册、登录、退出
- 登录验证码生成与校验
- 通过首页输入需求创建应用
- 上传图片、文本文件作为生成参考资料
- 支持直接粘贴截图到输入区域
- 查看”我的作品”
- 在应用对话页继续与 AI 交互
- 停止当前生成任务
- 预览生成结果
- 下载应用源码
- 一键部署应用
- 浏览模板广场并复用模板
- 用户个人中心展示 VIP 等级及剩余使用额度
- 对话页支持 **RAG 知识库开关**，按需开启/关闭模板参考

### 管理后台功能

- 用户管理（含 VIP 等级编辑、额度调整）
- 应用管理
- 对话管理
- 日志管理
- 数据统计（含创作趋势、活跃用户等可视化图表）

### AI 与生成能力

- 自动识别代码生成类型
- 支持 HTML、多文件页面、Vue 项目等生成方式
- 支持思考过程/工具调用消息展示
- 支持生成结果解析与文件落盘
- 支持 Vue 项目构建
- 支持生成应用封面截图
- **RAG 知识库检索**：
  - 已部署项目自动入库，按文件类型（Vue/HTML/JS/CSS）分割索引
  - 支持 `bge-small-zh-v1.5` 中文嵌入模型
  - 支持 `ConditionalContentRetriever` 运行时动态开关
  - 知识库可持久化（`InMemoryEmbeddingStore` 文件序列化 / Qdrant 向量数据库）
- **Function Calling 工具调用**：
  - **文件系统操作**：`FileReadTool` / `FileModifyTool` / `FileWriteTool` ——AI 直接读写和修改代码文件，避免全量重生成
  - **网页内容获取**：`WebFetchTool` ——AI 主动访问外部网站参考设计风格和布局
  - **Skills 技能系统**：通过 `activate_skill` 动态加载设计规范、最佳实践，提升代码质量
- **流式取消**：`StreamingHandle.cancel()` 源头截断，停止 AI 生成并终止计费
- **输入安全护轨**：Prompt 注入检测与过滤

---

## 技术栈

### 后端

| 技术         | 版本                           |
| ------------ | ------------------------------ |
| Java         | 21                             |
| Spring Boot  | 3.5.4                          |
| MyBatis-Flex | 1.11.0                         |
| LangChain4j  | 1.15.0                         |
| MySQL        | 8.x                            |
| Redis        | (session / cache / AI memory)  |
| Hutool       | 5.8.38                         |
| Knife4j      | 4.4.0                          |
| Qdrant       | 1.17.0 (向量数据库)            |
| 嵌入模型     | BAAI bge-small-zh-v1.5 (512维) |

### 前端

| 技术           | 版本   |
| -------------- | ------ |
| Vue            | 3.5.17 |
| Vite           | 7.x    |
| Ant Design Vue | 4.2.6  |
| TypeScript     | ~5.8   |
| Pinia          | 3.0.3  |
| Vue Router     | 4.5.1  |
| Axios          | 1.11.0 |

### AI 模型

| 用途                | 模型                         |
| ------------------- | ---------------------------- |
| 代码生成 / 工具调用 | DeepSeek `deepseek-v4-flash` |
| 路由分类 / 意图识别 | Qwen-turbo                   |
| 嵌入模型            | BAAI bge-small-zh-v1.5       |

---

## 项目结构

```text
ai_code_platform_project/
├─ ai-code-platform-backend/
│  ├─ src/main/java/com/ai/codeplatform/
│  │  ├─ ai/                # AI 服务与模型适配
│  │  ├─ annotation/        # 注解
│  │  ├─ aop/               # AOP 与日志等切面
│  │  ├─ common/            # 通用返回与公共类
│  │  ├─ config/            # 配置类
│  │  ├─ constant/          # 常量
│  │  ├─ controller/        # 控制器
│  │  ├─ core/              # 代码生成、解析、保存、构建等核心逻辑
│  ├─ rag/               # RAG 知识库（分割器、文档加载、向量存储、检索器）
│  │  ├─ exception/         # 异常处理
│  │  ├─ manager/           # 管理器（如 COS、取消生成等）
│  │  ├─ mapper/            # 数据访问层
│  │  ├─ model/             # DTO / Entity / VO
│  │  ├─ ratelimiter/       # 限流模块
│  │  ├─ service/           # 业务服务
│  │  ├─ utils/             # 工具类
│  │  └─ AiCodePlatformApplication.java
│  ├─ src/main/resources/
│  │  ├─ application.yml
│  │  ├─ application-dev.yml
│  │  ├─ application-pro.yml
│  │  ├─ mapper/
│  │  └─ prompt/
│  ├─ sql/                  # 建表语句
│  └─ tmp/                  # 生成代码、构建输出、部署等中间目录
│
├─ ai-code-platform-frontend/
│  ├─ src/
│  │  ├─ api/               # 前端接口封装
│  │  ├─ components/        # 公共组件
│  │  ├─ config/            # 环境变量与 URL 配置
│  │  ├─ layouts/
│  │  ├─ pages/             # 页面
│  │  ├─ router/
│  │  ├─ stores/            # Pinia 状态管理
│  │  └─ utils/
│  ├─ public/
│  └─ vite.config.ts
```

---

## 架构流程概览

```text
用户输入需求
  -> 前端创建应用
  -> 跳转到应用对话页
  -> 发送文本与参考资料到后端
  -> 后端调用 AI 进行代码生成
  -> 生成过程以流式消息返回前端
  -> 后端解析并保存代码文件
  -> 前端展示预览结果
  -> 用户继续对话 / 停止生成 / 下载代码 / 部署应用
```

---

## 环境要求

建议本地准备以下环境：

- JDK 21
- Maven 3.9+
- Node.js 18 及以上
- npm
- MySQL 8.x
- Redis 6.x / 7.x
- Qdrant 1.17（可选，可用 InMemory 回退）

如果你要完整运行截图、部署、对象存储等能力，还需要准备：

- 可用的大模型 API Key
- 腾讯云 COS 账号配置
- 可运行 Selenium 的浏览器环境

---

## 后端配置说明

后端默认配置入口见：

- [application.yml](G:\JAVA\project\ai_code_platform_project\ai-code-platform-backend\src\main\resources\application.yml)
- [application-dev.yml](G:\JAVA\project\ai_code_platform_project\ai-code-platform-backend\src\main\resources\application-dev.yml)
- [application-pro.yml](G:\JAVA\project\ai_code_platform_project\ai-code-platform-backend\src\main\resources\application-pro.yml)

默认后端服务信息：

- 端口：`8082`
- 上下文路径：`/api`

你至少需要根据本地环境修改以下配置：

- MySQL 连接地址、用户名、密码
- Redis 地址、端口、密码
- 大模型接口地址、模型名、API Key
- COS 配置
- 应用部署域名配置

---

## 前端配置说明

关键前端配置文件：

- [vite.config.ts](G:\JAVA\project\ai_code_platform_project\ai-code-platform-frontend\vite.config.ts)
- [src/config/env.ts](G:\JAVA\project\ai_code_platform_project\ai-code-platform-frontend\src\config\env.ts)
- [ai-code-platform-frontend/.env.development](G:\JAVA\project\ai_code_platform_project\ai-code-platform-frontend\.env.development)

开发环境默认通过 Vite 代理访问后端：

- `VITE_API_BASE_URL=/api`
- `/api` 请求会被代理到 `http://localhost:8082`

---

## 数据库初始化

项目根目录提供了数据库脚本：

- [create_table.sql]

初始化步骤如下：

1. 创建数据库，例如：`ai_code_platform`
2. 执行 SQL 脚本 `create_table.sql.sql`
3. 修改后端 `application-dev.yml` 中的数据源连接配置

---

## 启动方式

### 1. 启动后端

进入后端目录：

```bash
cd ai-code-platform-backend
```

使用 Maven 启动：

```bash
mvn spring-boot:run
```

或者在 Windows 下使用 Maven Wrapper：

```bash
.\mvnw.cmd spring-boot:run
```

启动成功后常用地址：

- 后端接口根地址：`http://localhost:8082/api`
- 接口文档：`http://localhost:8082/api/doc.html`

### 2. 启动前端

进入前端目录：

```bash
cd ai-code-platform-frontend
```

安装依赖：

```bash
npm install
```

启动开发服务器：

```bash
npm run dev
```

启动后访问：

- `http://localhost:5173`

---

## 构建命令

### 后端构建

```bash
mvn clean package -DskipTests
```

### 前端构建

```bash
npm run build
```

如果只需要执行前端 Vite 打包：

```bash
npm run build-only
```

---

## 主要页面说明

### 前台页面

- 首页：输入需求、上传参考资料、查看我的作品

  <img width="2560" height="1199" alt="image" src="https://github.com/user-attachments/assets/6c308814-7631-42cc-97bc-0b336079c5e4" />


- 登录页：账号密码 + 验证码登录

 <img width="2560" height="1199" alt="image" src="https://github.com/user-attachments/assets/495eb7b3-070e-4616-bbe1-2f0445b7fb70" />

- 注册页：用户注册

  <img width="2560" height="1199" alt="image" src="https://github.com/user-attachments/assets/4206e3ce-a2e3-4e92-8b20-68183c402bc6" />

- 个人中心：查看与修改个人资料

 <img width="2560" height="1199" alt="image" src="https://github.com/user-attachments/assets/55afe40c-6ba5-4df6-a691-dbf08758305e" />


- 应用对话页：继续生成、上传附件、停止生成、部署、下载、预览

  <img width="2560" height="1199" alt="image" src="https://github.com/user-attachments/assets/ef3fad4f-c02c-4cb4-966d-982222ef43e7" />


- 模板广场：查看精选模板并复用

 <img width="2560" height="1199" alt="image" src="https://github.com/user-attachments/assets/8aa76679-2bdf-4fbc-8756-c38dddde70bf" />


### 后台页面

- 用户管理

  <img width="2560" height="1199" alt="image" src="https://github.com/user-attachments/assets/35bc14cf-2bf9-489b-8c9f-06053027a18c" />


- 应用管理

 <img width="2560" height="1199" alt="image" src="https://github.com/user-attachments/assets/fd58de41-2b3c-4548-8115-d9ed545f5950" />


- 日志管理

  <img width="2560" height="1199" alt="image" src="https://github.com/user-attachments/assets/350afd0f-9d3c-4fb0-8475-a69c3d7257d7" />


- 统计页面

  <img width="2560" height="1199" alt="image" src="https://github.com/user-attachments/assets/8190c79c-f8f3-4d14-bf27-4f911150f044" />


---

## 主要接口模块

后端核心控制器包括：

- `UserController`：注册、登录、验证码、个人信息、VIP 等级与额度
- `AppController`：应用创建、AI 对话生成（含 `useRag` 参数）、取消生成、下载、部署、模板复用
- `ChatHistoryController`：对话历史查询
- `StatisticsController`：统计数据（创作趋势、成功率、活跃用户等）
- `OperationLogController`：日志相关

---

## 运行流程

1. 用户登录系统
2. 在首页输入需求并创建应用
3. 进入应用对话页与 AI 继续交互
4. 后端根据需求选择合适的生成模式
5. AI 以流式方式返回内容
6. 后端落盘生成代码并输出预览
7. 用户可继续迭代、停止任务、下载源码或部署应用

---

## License

```text
This project is for learning, experimentation, and graduation design purposes.
```

