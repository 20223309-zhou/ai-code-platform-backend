<p align="center">
  <img src="assets/logo.png" width="120" height="120" alt="iCodeAI" />
</p>

<h1 align="center">iCodeAI</h1>

<p align="center"><b>一句话描述需求，AI 直接生成可运行、可迭代、可部署的网页应用</b></p>

<p align="center">
  <b>简体中文</b> ·
  <a href="README.en.md">English</a> ·
  <a href="http://www.icodeplay.site">在线体验</a> ·
  <a href="#快速开始">快速开始</a> ·
  <a href="#桌面端">桌面端</a> ·
  <a href="https://github.com/20223309-zhou/ai-code-platform-frontend">前端仓库</a> ·
  <a href="https://github.com/20223309-zhou/ai-code-platform-backend-microservice">微服务版</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white" alt="Java" />
  <img src="https://img.shields.io/badge/Spring_Boot-3.5.4-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot" />
  <img src="https://img.shields.io/badge/LangChain4j-1.15-000000?logo=langchain" alt="LangChain4j" />
  <img src="https://img.shields.io/badge/MyBatis--Flex-1.11-FF6A00" alt="MyBatis-Flex" />
  <img src="https://img.shields.io/badge/ShardingSphere-5.5-1F6FEB" alt="ShardingSphere" />
  <img src="https://img.shields.io/badge/Qdrant-1.17-EE3664?logo=qdrant" alt="Qdrant" />
  <img src="https://img.shields.io/badge/MySQL-8-4479A1?logo=mysql&logoColor=white" alt="MySQL" />
  <img src="https://img.shields.io/badge/Redis-7.2-DC382D?logo=redis&logoColor=white" alt="Redis" />
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Vue-3.5-4FC08D?logo=vuedotjs&logoColor=white" alt="Vue" />
  <img src="https://img.shields.io/badge/React-18-61DAFB?logo=react&logoColor=black" alt="React" />
  <img src="https://img.shields.io/badge/TypeScript-5.8-3178C6?logo=typescript&logoColor=white" alt="TypeScript" />
  <img src="https://img.shields.io/badge/Vite-7-646CFF?logo=vite&logoColor=white" alt="Vite" />
  <img src="https://img.shields.io/badge/Electron-33-47848F?logo=electron&logoColor=white" alt="Electron" />
  <img src="https://img.shields.io/badge/Windows-x64-0078D6?logo=windows&logoColor=white" alt="Windows" />
</p>

---

> 本仓库是**单体版**，一个进程跑完整业务。微服务拆分版（Spring Cloud Alibaba + Gateway + OpenFeign + Nacos + RabbitMQ）见
> [ai-code-platform-backend-microservice](https://github.com/20223309-zhou/ai-code-platform-backend-microservice)。

---

<table>
  <tr>
    <td align="center" width="25%">
      <b>自然语言生成</b><br />
      <sub>描述业务目标即可出完整页面<br />支持单页、多文件、Vue 与 React 工程</sub>
    </td>
    <td align="center" width="25%">
      <b>对话式迭代</b><br />
      <sub>多轮修改、局部改文件<br />不必每次推倒重来</sub>
    </td>
    <td align="center" width="25%">
      <b>RAG 知识库</b><br />
      <sub>已部署项目自动入库<br />按项目类型检索同类实现</sub>
    </td>
    <td align="center" width="25%">
      <b>桌面端可选</b><br />
      <sub>Electron 封装，托盘常驻<br />多窗口并行，长任务不中断</sub>
    </td>
  </tr>
</table>

---

## 界面预览

<p align="center">
  <img src="assets/首页-亮色.png" alt="首页：输入需求开始生成" width="100%" />
  <br />
  <img src="assets/首页-深色.png" alt="首页：输入需求开始生成" width="100%" />
  <sub>首页 —— 输入需求、上传参考资料，选择生成类型与模型；侧边栏常驻公告、签到积分与主题切换</sub>
</p>

<p align="center">
  <img src="assets/对话页-亮色.png" alt="应用对话页：左侧对话，右侧实时预览" width="100%" />
  <br />
  <img src="assets/对话页-深色.png" alt="应用对话页：左侧对话，右侧实时预览" width="100%" />
  <sub>对话页 —— 左侧与 AI 对话迭代，右侧实时预览生成结果</sub>
</p>

<p align="center">
  <img src="assets/模板广场-亮色.png" alt="模板广场" width="100%" />
  <br />
  <img src="assets/模板广场-深色.png" alt="模板广场" width="100%" />
  <sub>模板广场 —— 浏览精选模板并一键复用</sub>
</p>

---

## 核心能力

### AI 与生成

- **生成类型自动路由**：由路由模型判断该产出单文件 HTML、多文件页面、Vue 工程还是 React 工程，用户也可手动指定
- **多模态输入**：支持上传图片（识图取风格）与 txt / md 文本作为参考资料，也支持直接粘贴截图
- **流式输出**：生成过程逐段返回前端，包含思考过程与工具调用消息；支持**停止生成**（`StreamingHandle.cancel()` 在源头截断，同时终止计费）
- **方案预选**：需求存在设计分歧时，先生成多套设计方案供选择，需求明确则直接进入生成；方案由独立的轻量模型产出，与代码生成主模型解耦，失败或超时自动降级，不阻断主流程
- **会话记忆**：基于 Redis 的多轮上下文，历史回放按 token 预算裁剪，压缩后仍保留关键约束

### RAG 知识库

- 已部署项目自动入库，按文件类型分开索引：Vue / React 走工程级分割（React 以文件为单位整块入库，并附加中文功能描述），HTML / JS / CSS 走文本分割
- 检索时按当前生成类型动态过滤（`dynamicFilter`），Vue 工程不会检索到 React 片段
- 嵌入模型 `bge-small-zh-v1.5`（512 维），向量库可选 Qdrant 或 InMemory（可持久化序列化）
- `ConditionalContentRetriever` 支持运行时开关，对话页可随时关闭模板参考

### Function Calling

| 工具 | 作用 |
| --- | --- |
| `FileReadTool` / `FileWriteTool` / `FileModifyTool` | AI 直接读写、局部修改代码文件，避免全量重新生成 |
| `WebFetchTool` | 主动访问外部网站，参考其设计风格与布局 |
| `activate_skill` | 按需加载设计规范与最佳实践（Skills 技能系统） |

### 平台能力

- 应用预览、源码下载、一键部署
- 模板广场与模板复用
- **公告中心**：进站弹最新未读，侧边栏常驻入口可回看全部可见公告并清红点；前端会话内共享一份数据（60 秒失效），后端可见公告走 Redis 缓存（30 分钟 TTL），发布 / 编辑 / 下线时主动失效
- **签到与积分**：每日签到累计积分并记录连续天数，积分商城可兑换生成额度，积分流水可查
- **主题系统**：4 套主题（石墨专业 / 终端青 / 温润中性 / 瑞士排版），CSS 变量数据驱动，圆角、阴影、过渡随主题一起变
- VIP 等级与使用额度控制
- 管理后台：用户、应用、对话、公告、日志管理与数据统计可视化
- 输入安全护轨：Prompt 注入检测与过滤

---

## 技术栈

| 层次 | 技术 |
| --- | --- |
| 后端 | Java 21 · Spring Boot 3.5.4 · MyBatis-Flex 1.11 · ShardingSphere 5.5（主从读写分离）· Redisson · Hutool 5.8 · Knife4j 4.4 |
| AI | LangChain4j 1.15 · Qdrant 1.17 · bge-small-zh-v1.5 |
| 存储 | MySQL 8（主从）· Redis 7.2（Session / 缓存 / 会话记忆 / 分布式限流） |
| 前端 | Vue 3.5 · Vite 7 · Ant Design Vue 4.2 · TypeScript 5.8 · Pinia 3 · Vue Router 4 |
| 桌面端 | Electron 33 · electron-builder（NSIS 安装包） |
| 微服务版 | Spring Cloud Alibaba 2025 · Gateway · OpenFeign · Nacos · RabbitMQ |

模型分工：

| 用途 | 模型 |
| --- | --- |
| 代码生成 / 工具调用 | DeepSeek `deepseek-v4.1-flash`（可切 `grok-4.7` / `gpt-6.1-sol`） |
| 路由分类 / 意图识别 | Qwen `qwen-flash`（100 token 枚举值） |
| 方案预选 | Qwen `qwen-flash`（2000 token，输出结构化 JSON） |
| 向量嵌入 | BAAI `bge-small-zh-v1.5` |

---

## 项目结构

```text
ai-code-platform-backend/
├─ src/main/java/com/ai/codeplatform/
│  ├─ ai/           # AI Service、模型适配、方案生成
│  ├─ annotation/   # 自定义注解
│  ├─ aop/          # 切面（鉴权、日志）
│  ├─ common/       # 统一返回与公共类
│  ├─ config/       # 配置类（主模型 / 路由 / 方案模型、缓存、安全、邮件）
│  ├─ constant/     # 常量
│  ├─ controller/   # 控制器
│  ├─ core/         # 代码生成、解析、保存、构建
│  ├─ exception/    # 异常与错误码
│  ├─ generator/    # 代码生成器
│  ├─ manager/      # COS、生成任务取消等
│  ├─ mapper/       # 数据访问层
│  ├─ model/        # DTO / Entity / VO
│  ├─ rag/          # 分割器（Vue / React / HTML / JS）、文档加载、检索器
│  ├─ ratelimiter/  # 限流
│  ├─ service/      # 业务服务
│  └─ utils/        # 工具类
├─ src/main/resources/
│  ├─ application.yml / application-dev.yml / application-pro.yml
│  ├─ sharding-dev.yml / sharding-pro.yml   # 主从读写分离规则
│  ├─ prompt/       # HTML / 多文件 / Vue / React / 方案 / 路由系统提示词
│  └─ skills/       # 平台内置 AI Skills
├─ sql/             # 全量建表 + 按日期命名的增量脚本
└─ tmp/             # 生成代码、构建输出、部署产物
```

---

## 快速开始

### 环境要求

- JDK 21、Maven 3.9+
- Node.js 18+、npm
- MySQL 8.x（配置里是主从两实例，单机调试可先指向同一实例）
- Redis 6/7
- Qdrant 1.17（可选，可用 InMemory 回退）
- 完整跑通截图 / 部署 / 对象存储，另需大模型 API Key、腾讯云 COS、可运行 Selenium 的浏览器

### 1. 初始化数据库

```sql
CREATE DATABASE ai_code_platform DEFAULT CHARACTER SET utf8mb4;
```

新库直接执行 `sql/create_table.sql`（已包含公告与积分表）；已有库按日期顺序执行 `sql/` 下的增量脚本：

```text
sql/2026-10-10_add_user_email.sql
sql/2026-10-29_add_announcement.sql
sql/2026-10-29_add_point_system.sql
```

### 2. 启动后端

```bash
mvn spring-boot:run
# Windows 可改用：.\mvnw.cmd spring-boot:run
```

- 接口根地址：`http://localhost:8082/api`
- 接口文档：`http://localhost:8082/api/doc.html`

### 3. 启动前端

```bash
cd ai-code-platform-frontend
npm install
npm run dev
```

访问 `http://localhost:5173`，开发环境由 Vite 把 `/api` 代理到 `http://localhost:8082`。

### 必改配置

数据源走 ShardingSphere 驱动，`application-dev.yml` 里只有规则文件路径，真实连接在 `sharding-*.yml`：

| 配置项 | 位置 |
| --- | --- |
| MySQL 主库 / 从库连接 | `src/main/resources/sharding-dev.yml`（`master` / `slave`） |
| Redis 连接 | `src/main/resources/application-dev.yml` |
| 大模型地址、模型名、API Key | `application-dev.yml` 的 `langchain4j` 段 |
| 邮箱授权码（注册验证码） | `application-dev.yml` 的 `spring.mail` |
| COS 对象存储 | `application-dev.yml` |
| 应用部署域名 | `application-dev.yml`（对应 `code.deploy-path`） |

> dev 档位里是明文密钥，只用于本地。生产用 `application-pro.yml`，敏感项全部走环境变量（`SPRING_MYSQL_*`、`SPRING_REDIS_*`、`MAIL_*` 等）。

### 构建

```bash
# 后端
mvn clean package -DskipTests

# 前端
npm run build
```

---

## 桌面端

同一套前端可直接封装为 Windows 桌面应用，**后端无需改动**：

- 壳内启动本地服务，同一端口托管静态资源并代理 `/api`，请求算同站，Session Cookie 与预览 iframe 都不需要额外配置
- 系统托盘常驻：关闭窗口只是隐藏，AI 生成长任务不会被打断
- **多窗口并行**：每个作品可以单独开一个窗口同时生成，重复点开同一作品会复用已有窗口，不会重复开壳
- 4 套主题（含深色）、应用式侧边栏布局

安装包见 [Releases](../../releases)，源码在 [ai-code-platform-frontend](https://github.com/20223309-zhou/ai-code-platform-frontend) 的 `desktop` 分支。

---

## 主要接口模块

| 控制器 | 职责 |
| --- | --- |
| `UserController` | 注册、登录（图形验证码 / GitHub OAuth）、个人信息、VIP 等级与额度 |
| `AppController` | 应用创建、AI 对话生成（含 `useRag`）、取消生成、下载、部署、模板复用、方案预选 |
| `ChatHistoryController` | 对话历史查询 |
| `AnnouncementController` | 用户端可见公告（免登录）、管理端公告增删改查与发布 |
| `PointController` | 积分账户、每日签到、商城商品、兑换生成额度、积分流水 |
| `StatisticsController` | 创作趋势、成功率、活跃用户等统计数据 |
| `SysOperationLogController` | 操作日志（管理端） |

---

## License

```text
This project is for learning, experimentation, and graduation design purposes.
```
