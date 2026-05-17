# iCodeAI — AI 驱动的应用生成平台

icodeAI 是一个基于 **Langchain4j + Spring Boot + Vue 3 + 大模型能力** 构建的智能应用生成平台。

<img width="2560" height="1199" alt="image-20260517141948477" src="https://github.com/user-attachments/assets/3d80d521-1041-4a72-8d2c-f52f5de784a4" />


用户可以通过自然语言描述需求，快速生成网页应用原型，并继续通过对话方式迭代修改。平台同时支持上传图片/文本文件作为参考资料、预览生成结果、下载源码、部署应用、模板复用以及后台管理等功能。

本仓库包含两个子项目：

- `ai-code-platform-backend`：后端服务
- `ai-code-platform-frontend`：前端界面 https://github.com/20223309-zhou/ai-code-platform-frontend

---

## 项目特色

- 自然语言驱动的应用生成
- 支持图片、文本文件作为生成参考
- 支持流式返回 AI 生成过程
- 支持多轮对话式迭代修改
- 支持停止生成任务
- 支持应用预览、代码下载、应用部署
- 支持模板广场与模板复用
- 支持管理员后台管理用户、应用、对话、日志和统计

---

## 主要功能

### 用户侧功能

- 用户注册、登录、退出
- 登录验证码生成与校验
- 通过首页输入需求创建应用
- 上传图片、文本文件作为生成参考资料
- 支持直接粘贴截图到输入区域
- 查看“我的作品”
- 在应用对话页继续与 AI 交互
- 停止当前生成任务
- 预览生成结果
- 下载应用源码
- 一键部署应用
- 浏览模板广场并复用模板

### 管理后台功能

- 用户管理
- 应用管理
- 对话管理
- 日志管理
- 数据统计

### AI 与生成能力

- 自动识别代码生成类型
- 支持 HTML、多文件页面、Vue 项目等生成方式
- 支持思考过程/工具调用消息展示
- 支持生成结果解析与文件落盘
- 支持 Vue 项目构建
- 支持生成应用封面截图

---

## 技术栈

### 后端

- Java 21
- Spring Boot 3
- MyBatis-Flex
- MySQL
- Redis
- Spring Session
- LangChain4j
- Redisson
- Hutool
- Knife4j / SpringDoc OpenAPI
- Selenium + WebDriverManager
- 腾讯云 COS

### 前端

- Vue 3
- TypeScript
- Vite
- Pinia
- Vue Router
- Ant Design Vue
- Axios
- Markdown-It
- Highlight.js

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
│  ├─ sql/
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
│
└─ ai_code_platform.sql     # 数据库初始化脚本
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

- [ai_code_platform.sql](G:\JAVA\project\ai_code_platform_project\ai_code_platform.sql)

初始化步骤如下：

1. 创建数据库，例如：`ai_code_platform`
2. 执行 SQL 脚本 `ai_code_platform.sql`
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

  <img width="2560" height="1199" alt="image-20260517141948477" src="https://github.com/user-attachments/assets/e33e63a5-dfba-45b9-9c60-499503a188ec" />


- 登录页：账号密码 + 验证码登录

  <img width="2560" height="1199" alt="image-20260517142241164" src="https://github.com/user-attachments/assets/be211c5c-a8e2-4756-ba73-7bb9ea98582d" />


- 注册页：用户注册

  <img width="2560" height="1199" alt="image-20260517142300084" src="https://github.com/user-attachments/assets/164db93e-2541-4ab9-adbe-ade6d939e387" />


- 个人中心：查看与修改个人资料

  <img width="2560" height="1199" alt="image-20260517142323339" src="https://github.com/user-attachments/assets/6e24e18b-1a1c-4962-b09f-406fb4c63c4a" />


- 应用对话页：继续生成、上传附件、停止生成、部署、下载、预览

  <img width="2560" height="1199" alt="image-20260517142351449" src="https://github.com/user-attachments/assets/f6fcfa12-51eb-4b33-99d7-1a887328d058" />


- 模板广场：查看精选模板并复用

  <img width="2560" height="1199" alt="image-20260517142414309" src="https://github.com/user-attachments/assets/b42079b6-4de4-44e7-a7c3-44f810ff6898" />


### 后台页面

- 用户管理

  <img width="2560" height="1199" alt="image-20260517142435968" src="https://github.com/user-attachments/assets/b9e8f1fb-2817-449f-ad61-34373711e93d" />


- 应用管理

  <img width="2560" height="1199" alt="image-20260517142452134" src="https://github.com/user-attachments/assets/82ab0c5d-5166-4e87-af41-efb1ed04f617" />


- 日志管理

  <img width="2560" height="1199" alt="image-20260517142517393" src="https://github.com/user-attachments/assets/d30c5226-4b25-49aa-ab27-4db0c2644463" />


- 统计页面

  <img width="2560" height="1199" alt="image-20260517142525593" src="https://github.com/user-attachments/assets/1eba98cd-9f20-4c16-a56d-84c6fcdf15e0" />


---

## 主要接口模块

后端核心控制器包括：

- `UserController`：注册、登录、验证码、个人信息
- `AppController`：应用创建、AI 对话生成、取消生成、下载、部署、模板复用
- `ChatHistoryController`：对话历史查询
- `StatisticsController`：统计数据
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

