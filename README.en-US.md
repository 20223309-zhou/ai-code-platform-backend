# iCodeAI — AI-Driven Application Generation Platform
![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.4-6DB33F?logo=springboot)
![LangChain4j](https://img.shields.io/badge/LangChain4j-1.15-000000?logo=langchain)
![MyBatis-Flex](https://img.shields.io/badge/MyBatis_Flex-1.11-FF6A00?logo=mybatis)
![Qdrant](https://img.shields.io/badge/Qdrant-1.17-EE3664?logo=qdrant)
![MySQL](https://img.shields.io/badge/MySQL-8-4479A1?logo=mysql)
![Redis](https://img.shields.io/badge/Redis-7.2-DC382D?logo=redis)
![Vue.js](https://img.shields.io/badge/Vue.js-3.5-4FC08D?logo=vuedotjs)
![TypeScript](https://img.shields.io/badge/TypeScript-5.8-3178C6?logo=typescript)


iCodeAI is an intelligent application generation platform built based on **Langchain4j + RAG + Qdrant + Spring Boot + Vue 3 + Large Language Model (LLM) capabilities**.

<img width="2560" height="1199" alt="image" src="https://github.com/user-attachments/assets/9ef2989b-b1b6-4edd-94ee-6b65e83365e3" />


Users can describe their requirements in natural language to quickly generate web application prototypes and continue iterating through conversation. The platform also supports uploading images/text files as reference materials, previewing generated results, downloading source code, deploying applications, template reuse, and backend management.

This repository contains two sub-projects:

- `ai-code-platform-backend`: Backend service
- `ai-code-platform-frontend`: Frontend interface https://github.com/20223309-zhou/ai-code-platform-frontend

---

## Project Features

- Natural language-driven application generation
- Multi-modal recognition: supports images and text files as generation references
- **RAG Knowledge Base Retrieval** — Uses deployed template code as reference corpora to improve generation consistency and success rates
- **Function Calling** — AI automatically invokes tools for file read/write and web fetching to achieve smarter code generation
- Streaming response for the AI generation process
- Multi-turn conversational iterative modifications
- Ability to stop generation tasks (intercepts AI output at the source)
- Application preview, code download, and application deployment
- Template gallery and template reuse
- Admin backend for managing users, applications, conversations, logs, and statistics
- VIP membership system — Level management + usage quota control

---

## Main Functionalities

### User Features

- User registration, login, and logout
- Login verification code generation and validation
- Create applications by entering requirements on the homepage
- Upload images and text files as reference materials
- Direct pasting of screenshots into the input area
- View "My Works"
- Continue interacting with AI on the application conversation page
- Stop the current generation task
- Preview generated results
- Download application source code
- One-click application deployment
- Browse the template gallery and reuse templates
- Personal center displaying VIP level and remaining usage quota
- **RAG Knowledge Base switch** on the conversation page to enable/disable template references as needed

### Admin Backend Features

- User management (including VIP level editing and quota adjustment)
- Application management
- Conversation management
- Log management
- Data statistics (visual charts for creation trends, active users, etc.)

### AI and Generation Capabilities

- Automatic recognition of code generation type
- Supports HTML, multi-file pages, Vue projects, etc.
- Displays thinking process and tool-calling messages
- Parsing of generation results and file persistence
- Vue project build support
- Automatic generation of application cover screenshots
- **RAG Knowledge Base Retrieval**:
  - Automatically indexes deployed projects, segmented by file type (Vue/HTML/JS/CSS)
  - Supports `bge-small-zh-v1.5` Chinese embedding model
  - Supports `ConditionalContentRetriever` for dynamic runtime switching
  - Persistent knowledge base (`InMemoryEmbeddingStore` file serialization / Qdrant vector database)
- **Function Calling**:
  - **File System Operations**: `FileReadTool` / `FileModifyTool` / `FileWriteTool` — AI directly reads, writes, and modifies code files to avoid full regeneration
  - **Web Content Retrieval**: `WebFetchTool` — AI actively visits external websites to reference design styles and layouts
  - **Skills System**: Dynamically loads design specifications and best practices via `activate_skill` to improve code quality
- **Streaming Cancellation**: `StreamingHandle.cancel()` intercepts the source to stop AI generation and terminate billing
- **Input Safety Guardrails**: Prompt injection detection and filtering

---

## Technology Stack

### Backend

| Technology    | Version                       |
| ------------- | ---------------------------- |
| Java          | 21                           |
| Spring Boot   | 3.5.4                        |
| MyBatis-Flex  | 1.11.0                       |
| LangChain4j   | 1.15.0                       |
| MySQL         | 8.x                          |
| Redis         | (session / cache / AI memory) |
| Hutool        | 5.8.38                       |
| Knife4j       | 4.4.0                        |
| Qdrant        | 1.17.0 (Vector Database)      |
| Embedding     | BAAI bge-small-zh-v1.5 (512d)|

### Frontend

| Technology     | Version |
| -------------- | ------- |
| Vue            | 3.5.17 |
| Vite           | 7.x    |
| Ant Design Vue | 4.2.6  |
| TypeScript     | ~5.8   |
| Pinia          | 3.0.3  |
| Vue Router     | 4.5.1  |
| Axios          | 1.11.0 |

### AI Models

| Purpose                    | Model                        |
| -------------------------- | ---------------------------- |
| Code Gen / Function Call   | DeepSeek `deepseek-v4-flash` |
| Routing / Intent Detection | Qwen-turbo                  |
| Embedding Model            | BAAI bge-small-zh-v1.5      |

---

## Project Structure

```text
ai_code_platform_project/
├─ ai-code-platform-backend/
│  ├─ src/main/java/com/ai/codeplatform/
│  │  ├─ ai/                # AI services and model adaptation
│  │  ├─ annotation/        # Annotations
│  │  ├─ aop/               # AOP and logging aspects
│  │  ├─ common/            # Common responses and utility classes
│  │  ├─ config/            # Configuration classes
│  │  ├─ constant/          # Constants
│  │  ├─ controller/        # Controllers
│  │  ├─ core/              # Core logic for code generation, parsing, saving, and building
│  ├─ rag/               # RAG Knowledge Base (Splitters, Doc Loaders, Vector Store, Retrievers)
│  │  ├─ exception/         # Exception handling
│  │  ├─ manager/           # Managers (e.g., COS, cancellation)
│  │  ├─ mapper/            # Data access layer
│  │  ├─ model/             # DTO / Entity / VO
│  │  ├─ ratelimiter/       # Rate limiting module
│  │  ├─ service/           # Business services
│  │  ├─ utils/             # Utility classes
│  │  └─ AiCodePlatformApplication.java
│  ├─ src/main/resources/
│  │  ├─ application.yml
│  │  ├─ application-dev.yml
│  │  ├─ application-pro.yml
│  │  ├─ mapper/
│  │  └─ prompt/
│  ├─ sql/                  # Table creation scripts
│  └─ tmp/                  # Intermediate directories for generated code, build output, deployment, etc.
│
├─ ai-code-platform-frontend/
│  ├─ src/
│  │  ├─ api/               # Frontend API encapsulation
│  │  ├─ components/        # Common components
│  │  ├─ config/            # Environment variables and URL config
│  │  ├─ layouts/
│  │  ├─ pages/             # Pages
│  │  ├─ router/
│  │  ├─ stores/            # Pinia state management
│  │  └─ utils/
│  ├─ public/
│  └─ vite.config.ts
```

---

## Architecture Workflow Overview

```text
User inputs requirement
  -> Frontend creates application
  -> Redirect to application conversation page
  -> Send text and reference materials to backend
  -> Backend calls AI for code generation
  -> Generation process returned to frontend via streaming messages
  -> Backend parses and saves code files
  -> Frontend displays preview result
  -> User continues conversation / stops generation / downloads code / deploys application
```

---

## Environment Requirements

Recommended local environment:

- JDK 21
- Maven 3.9+
- Node.js 18 or higher
- npm
- MySQL 8.x
- Redis 6.x / 7.x
- Qdrant 1.17 (Optional, can fall back to InMemory)

To fully enable screenshot capabilities, deployment, and object storage, you also need:

- A valid LLM API Key
- Tencent Cloud COS account configuration
- A browser environment capable of running Selenium

---

## Backend Configuration

Default configuration entry points:

- [application.yml](G:\JAVA\project\ai_code_platform_project\ai-code-platform-backend\src\main\resources\application.yml)
- [application-dev.yml](G:\JAVA\project\ai_code_platform_project\ai-code-platform-backend\src\main\resources\application-dev.yml)
- [application-pro.yml](G:\JAVA\project\ai_code_platform_project\ai-code-platform-backend\src\main\resources\application-pro.yml)

Default backend service info:

- Port: `8082`
- Context Path: `/api`

You must modify the following configurations based on your local environment:

- MySQL connection address, username, password
- Redis address, port, password
- LLM API endpoint, model name, API Key
- COS configuration
- Application deployment domain configuration

---

## Frontend Configuration

Key frontend configuration files:

- [vite.config.ts](G:\JAVA\project\ai_code_platform_project\ai-code-platform-frontend\vite.config.ts)
- [src/config/env.ts](G:\JAVA\project\ai_code_platform_project\ai-code-platform-frontend\src\config\env.ts)
- [ai-code-platform-frontend/.env.development](G:\JAVA\project\ai_code_platform_project\ai-code-platform-frontend\.env.development)

In the development environment, the backend is accessed via Vite proxy:

- `VITE_API_BASE_URL=/api`
- `/api` requests are proxied to `http://localhost:8082`

---

## Database Initialization

Database scripts are provided in the root directory:

- [create_table.sql]

Initialization steps:

1. Create a database, e.g., `ai_code_platform`
2. Execute the SQL script `create_table.sql.sql`
3. Modify the data source connection configuration in the backend `application-dev.yml`

---

## How to Start

### 1. Start Backend

Go to the backend directory:

```bash
cd ai-code-platform-backend
```

Start using Maven:

```bash
mvn spring-boot:run
```

Or use Maven Wrapper on Windows:

```bash
.\mvnw.cmd spring-boot:run
```

Useful addresses after successful startup:

- Backend API root: `http://localhost:8082/api`
- API Documentation: `http://localhost:8082/api/doc.html`

### 2. Start Frontend

Go to the frontend directory:

```bash
cd ai-code-platform-frontend
```

Install dependencies:

```bash
npm install
```

Start the development server:

```bash
npm run dev
```

Access via:

- `http://localhost:5173`

---

## Build Commands

### Backend Build

```bash
mvn clean package -DskipTests
```

### Frontend Build

```bash
npm run build
```

If you only need to execute the Vite build:

```bash
npm run build-only
```

---

## Main Page Descriptions

### Frontend Pages

- Homepage: Enter requirements, upload references, view my works

  <img width="2560" height="1199" alt="image" src="https://github.com/user-attachments/assets/6c308814-7631-42cc-97bc-0b336079c5e4" />


- Login Page: Account/Password + Verification Code login

 <img width="2560" height="1199" alt="image" src="https://github.com/user-attachments/assets/495eb7b3-070e-4616-bbe1-2f0445b7fb70" />

- Registration Page: User registration

  <img width="2560" height="1199" alt="image" src="https://github.com/user-attachments/assets/4206e3ce-a2e3-4e92-8b20-68183c402bc6" />

- Personal Center: View and modify personal profile

 <img width="2560" height="1199" alt="image" src="https://github.com/user-attachments/assets/55afe40c-6ba5-4df6-a691-dbf08758305e" />


- Application Conversation Page: Continue generation, upload attachments, stop generation, deploy, download, preview

  <img width="2560" height="1199" alt="image" src="https://github.com/user-attachments/assets/ef3fad4f-c02c-4cb4-966d-982222ef43e7" />


- Template Gallery: View selected templates and reuse them

 <img width="2560" height="1199" alt="image" src="https://github.com/user-attachments/assets/8aa76679-2bdf-4fbc-8756-c38dddde70bf" />


### Admin Pages

- User Management

  <img width="2560" height="1199" alt="image" src="https://github.com/user-attachments/assets/35bc14cf-2bf9-489b-8c9f-06053027a18c" />


- Application Management

 <img width="2560" height="1199" alt="image" src="https://github.com/user-attachments/assets/fd58de41-2b3c-4548-8115-d9ed545f5950" />


- Log Management

  <img width="2560" height="1199" alt="image" src="https://github.com/user-attachments/assets/350afd0f-9d3c-4fb0-8475-a69c3d7257d7" />


- Statistics Page

  <img width="2560" height="1199" alt="image" src="https://github.com/user-attachments/assets/8190c79c-f8f3-4d14-bf27-4f911150f044" />

---

## Main Interface Modules

Core backend controllers include:

- `UserController`: Registration, login, verification codes, personal info, VIP levels and quotas
- `AppController`: Application creation, AI conversation generation (including `useRag` parameter), cancel generation, download, deploy, template reuse
- `ChatHistoryController`: Conversation history queries
- `StatisticsController`: Statistical data (creation trends, success rates, active users, etc.)
- `OperationLogController`: Log-related operations

---

## Runtime Flow

1. User logs into the system
2. Enter requirements on the homepage to create an application
3. Enter the application conversation page to interact further with AI
4. Backend selects the appropriate generation mode based on requirements
5. AI returns content via streaming
6. Backend persists the generated code and outputs a preview
7. User can continue iterating, stop the task, download source code, or deploy the application

---

## License

```text
This project is for learning, experimentation, and graduation design purposes.
```
