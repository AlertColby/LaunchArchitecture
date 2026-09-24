# Server Vertical Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付可独立测试的 OpenAPI 契约和 Kotlin 后端，使注册、登录、刷新、退出和笔记增删改查在 H2 上走通。

**Architecture:** `contracts/openapi.yaml` 是唯一共享物。`server/` 用 Spring Boot 提供 `/api/v1`。控制器只做 HTTP，服务处理规则，仓库访问表。访问令牌是 JWT，刷新令牌只在库里存哈希。

**Tech Stack:** Java 21、Gradle 8.14.3、Spring Boot 3.5.16、Kotlin 1.9.25、Spring Security OAuth2 Resource Server、Flyway 11.7.2、H2（本地与测试）、PostgreSQL（`prod`）。

## Global Constraints

- 共享物只有 `contracts/openapi.yaml`。Web 和 Android 按契约手写调用，第一版不生成客户端代码。
- 后端：Spring Boot 3，Kotlin。包名 `com.launcharchitecture`。分层为 controller → service → repository。
- 前缀 `/api/v1`。JSON 字段使用 camelCase。时间用 UTC 的 ISO-8601。标识用 UUID。
- 访问令牌是 JWT，有效期 15 分钟，`sub` 为用户 id。`expiresIn` 固定为 900。
- 刷新令牌有效期 14 天，服务端只存哈希。刷新时作废旧令牌并签发新令牌。退出只凭刷新令牌，不要求访问令牌。
- 密码只存 BCrypt 哈希。邮箱保存前转成小写，全库唯一。密码 8 到 72 个字符。显示名 1 到 40 个字符。
- 笔记标题 1 到 80 个字符，正文 0 到 4000 个字符。未传 `body` 时按空字符串保存。修改同时替换标题和正文。
- 笔记列表最多 100 条，按 `updatedAt` 降序，不返回总数。别人的笔记和不存在的笔记都返回 404 与 `NOTE_NOT_FOUND`。
- 每个响应都带 `X-Request-Id`。客户端传入则沿用，否则服务端生成 UUID。
- 失败正文为 `code`、`message`、`requestId`。校验失败 400 `VALIDATION_ERROR`。缺少、过期或无效的访问令牌 401 `UNAUTHENTICATED`。刷新令牌无效、过期或已作废 401 `INVALID_REFRESH_TOKEN`。邮箱已被注册 409 `EMAIL_ALREADY_USED`。
- 注册、登录、刷新返回 200 和令牌。退出返回 204 且无正文。新建笔记返回 201。列表、读取、修改返回 200。删除返回 204。健康检查返回 200 `{ "status": "up" }`，无需登录。
- 后端监听 8080。跨域允许源 `http://localhost:5173`，并允许头 `Authorization`、`Content-Type`、`X-Request-Id`。
- 本地配置使用 H2 文件库。`prod` 使用 PostgreSQL，连接信息来自 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`。JWT 签名密钥来自 `JWT_SECRET`；本地配置带一个仅用于开发的默认值。
- 表由 Flyway 管理：`users`、`refresh_tokens`、`notes`，字段与设计文档一致。
- 后端集成测试使用 H2，至少覆盖：注册后能访问当前用户、邮箱重复返回 409、登录、新建并列出笔记、修改并删除笔记、不能读取另一个用户的笔记、无效刷新令牌返回 401。
- 本计划不实现 Web、Android、`docs/acceptance.md`、`docs/adding-a-module.md`。

## 文件职责

- `contracts/openapi.yaml`：竖切的 HTTP 契约。
- `.gitignore`：忽略构建产物和本地 H2 文件。
- `server/build.gradle.kts`：构建和依赖。
- `server/src/main/kotlin/com/launcharchitecture/LaunchArchitectureApplication.kt`：启动入口。
- `server/src/main/kotlin/com/launcharchitecture/common/`：请求 ID、错误体、异常。
- `server/src/main/kotlin/com/launcharchitecture/config/`：安全、JWT、跨域、配置属性。
- `server/src/main/kotlin/com/launcharchitecture/health/HealthController.kt`：健康检查。
- `server/src/main/kotlin/com/launcharchitecture/auth/`：注册、登录、刷新、退出、当前用户 id。
- `server/src/main/kotlin/com/launcharchitecture/user/`：用户表和 `GET /api/v1/me`。
- `server/src/main/kotlin/com/launcharchitecture/note/`：笔记模块。
- `server/src/main/resources/db/migration/V1__init.sql`：三张表。
- `server/src/main/resources/application.yml`：本地 H2 文件库和开发用 JWT 默认密钥。
- `server/src/main/resources/application-prod.yml`：正式环境只读环境变量。
- `server/src/test/resources/application.yml`：测试用内存 H2，固定 JWT 密钥。
- `server/src/test/kotlin/com/launcharchitecture/`：集成测试。

命令在 `server/` 下执行，除非某步写明仓库根目录。Windows 使用 `.\gradlew.bat`。

---

### Task 1: 启动服务并提供健康检查

**Files:**
- Create: `.gitignore`
- Create: `server/settings.gradle.kts`
- Create: `server/build.gradle.kts`
- Create: `server/src/main/kotlin/com/launcharchitecture/LaunchArchitectureApplication.kt`
- Create: `server/src/main/kotlin/com/launcharchitecture/health/HealthController.kt`
- Create: `server/src/main/resources/application.yml`
- Create: `server/src/test/kotlin/com/launcharchitecture/health/HealthIntegrationTest.kt`
- Test: `server/src/test/kotlin/com/launcharchitecture/health/HealthIntegrationTest.kt`

**Interfaces:**
- Consumes: 无
- Produces: `GET /api/v1/health` 返回 `HealthResponse(status = "up")`，HTTP 200。`HealthResponse` 位于 `com.launcharchitecture.health`。

- [ ] **Step 1: 写构建文件和会失败的测试**

`.gitignore`：

```gitignore
.gradle/
build/
server/.gradle/
server/build/
server/data/
```

`server/settings.gradle.kts`：

```kotlin
rootProject.name = "server"
```

`server/build.gradle.kts`：

```kotlin
plugins {
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
}

group = "com.launcharchitecture"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
```

`server/src/main/kotlin/com/launcharchitecture/LaunchArchitectureApplication.kt`：

```kotlin
package com.launcharchitecture

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class LaunchArchitectureApplication

fun main(args: Array<String>) {
    runApplication<LaunchArchitectureApplication>(*args)
}
```

`server/src/main/resources/application.yml`：

```yaml
server:
  port: 8080
spring:
  jackson:
    serialization:
      write-dates-as-timestamps: false
    time-zone: UTC
```

`server/src/test/kotlin/com/launcharchitecture/health/HealthIntegrationTest.kt`：

```kotlin
package com.launcharchitecture.health

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
class HealthIntegrationTest(@Autowired val mockMvc: MockMvc) {
    @Test
    fun healthIsUp() {
        mockMvc.get("/api/v1/health")
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("up") }
            }
    }
}
```

- [ ] **Step 2: 生成 Wrapper 并确认测试失败**

在 `server/` 执行。若本机没有 `gradle`，先下载再生成 Wrapper：

```powershell
Invoke-WebRequest -Uri https://services.gradle.org/distributions/gradle-8.14.3-bin.zip -OutFile gradle-8.14.3-bin.zip
Expand-Archive gradle-8.14.3-bin.zip -DestinationPath .gradle-bootstrap
.\.gradle-bootstrap\gradle-8.14.3\bin\gradle.bat wrapper --gradle-version 8.14.3
Remove-Item -Recurse -Force .gradle-bootstrap, gradle-8.14.3-bin.zip
.\gradlew.bat test --tests com.launcharchitecture.health.HealthIntegrationTest
```

Expected: FAIL，`/api/v1/health` 的状态不是 200。

- [ ] **Step 3: 实现健康检查**

`server/src/main/kotlin/com/launcharchitecture/health/HealthController.kt`：

```kotlin
package com.launcharchitecture.health

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class HealthResponse(val status: String)

@RestController
@RequestMapping("/api/v1/health")
class HealthController {
    @GetMapping
    fun health(): HealthResponse = HealthResponse(status = "up")
}
```

- [ ] **Step 4: 确认测试通过**

Run: `.\gradlew.bat test --tests com.launcharchitecture.health.HealthIntegrationTest`

Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: Commit**

在仓库根目录：

```bash
git add .gitignore server
git commit -m "feat: add server health endpoint"
```

---

### Task 2: 请求 ID 和错误体

**Files:**
- Create: `server/src/main/kotlin/com/launcharchitecture/common/RequestIdFilter.kt`
- Create: `server/src/main/kotlin/com/launcharchitecture/common/ApiError.kt`
- Create: `server/src/main/kotlin/com/launcharchitecture/common/GlobalExceptionHandler.kt`
- Modify: `server/src/test/kotlin/com/launcharchitecture/health/HealthIntegrationTest.kt`
- Create: `server/src/test/kotlin/com/launcharchitecture/common/GlobalExceptionHandlerTest.kt`
- Test: 上述两个测试

**Interfaces:**
- Consumes: `GET /api/v1/health`
- Produces:
  - `RequestIdFilter.REQUEST_ID`：请求属性名，值为 `String`
  - `ApiError(code: String, message: String, requestId: String)`
  - `ErrorCodes.VALIDATION_ERROR`、`UNAUTHENTICATED`、`INVALID_REFRESH_TOKEN`、`NOTE_NOT_FOUND`、`EMAIL_ALREADY_USED`
  - `ApiException(status: HttpStatus, code: String, message: String)`
  - `GlobalExceptionHandler.handleApi`、`handleValidation`、`handleUnreadable`

- [ ] **Step 1: 写失败测试**

把 `HealthIntegrationTest` 换成：

```kotlin
package com.launcharchitecture.health

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
class HealthIntegrationTest(@Autowired val mockMvc: MockMvc) {
    @Test
    fun healthEchoesRequestId() {
        mockMvc.get("/api/v1/health") {
            header("X-Request-Id", "req-1")
        }.andExpect {
            status { isOk() }
            header { string("X-Request-Id", "req-1") }
        }
    }

    @Test
    fun healthGeneratesRequestId() {
        val headerValue = mockMvc.get("/api/v1/health")
            .andReturn()
            .response
            .getHeader("X-Request-Id")
        assertDoesNotThrow { UUID.fromString(headerValue) }
    }
}
```

`GlobalExceptionHandlerTest.kt`：

```kotlin
package com.launcharchitecture.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.mock.http.MockHttpInputMessage
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import java.lang.reflect.Method

class GlobalExceptionHandlerTest {
    private val handler = GlobalExceptionHandler()

    @Test
    fun apiExceptionUsesEnvelope() {
        val request = MockHttpServletRequest()
        request.setAttribute(RequestIdFilter.REQUEST_ID, "req-9")
        val response = handler.handleApi(
            ApiException(HttpStatus.NOT_FOUND, ErrorCodes.NOTE_NOT_FOUND, "笔记不存在"),
            request,
        )
        assertEquals(404, response.statusCode.value())
        assertEquals("NOTE_NOT_FOUND", response.body!!.code)
        assertEquals("笔记不存在", response.body!!.message)
        assertEquals("req-9", response.body!!.requestId)
    }

    @Test
    fun validationUsesEnvelope() {
        val request = MockHttpServletRequest()
        request.setAttribute(RequestIdFilter.REQUEST_ID, "req-2")
        val target = Any()
        val binding = BeanPropertyBindingResult(target, "request")
        binding.addError(FieldError("request", "email", "必须是邮箱"))
        val method = Sample::class.java.getDeclaredMethod("register", String::class.java)
        val parameter = org.springframework.core.MethodParameter(method, 0)
        val response = handler.handleValidation(
            MethodArgumentNotValidException(parameter, binding),
            request,
        )
        assertEquals(400, response.statusCode.value())
        assertEquals(ErrorCodes.VALIDATION_ERROR, response.body!!.code)
        assertEquals("req-2", response.body!!.requestId)
    }

    @Test
    fun unreadableJsonUsesValidationError() {
        val request = MockHttpServletRequest()
        request.setAttribute(RequestIdFilter.REQUEST_ID, "req-3")
        val response = handler.handleUnreadable(
            HttpMessageNotReadableException("bad", MockHttpInputMessage(ByteArray(0))),
            request,
        )
        assertEquals(400, response.statusCode.value())
        assertEquals(ErrorCodes.VALIDATION_ERROR, response.body!!.code)
        assertEquals("请求格式不正确", response.body!!.message)
    }

    class Sample {
        fun register(email: String) = email
    }
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `.\gradlew.bat test --tests com.launcharchitecture.health.HealthIntegrationTest --tests com.launcharchitecture.common.GlobalExceptionHandlerTest`

Expected: FAIL。健康检查没有 `X-Request-Id`，且 `com.launcharchitecture.common` 尚未定义。

- [ ] **Step 3: 实现过滤器和异常处理**

`RequestIdFilter.kt`：

```kotlin
package com.launcharchitecture.common

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val incoming = request.getHeader(HEADER)
        val requestId = if (incoming.isNullOrBlank()) UUID.randomUUID().toString() else incoming
        request.setAttribute(REQUEST_ID, requestId)
        response.setHeader(HEADER, requestId)
        filterChain.doFilter(request, response)
    }

    companion object {
        const val HEADER = "X-Request-Id"
        const val REQUEST_ID = "requestId"
    }
}
```

`ApiError.kt`：

```kotlin
package com.launcharchitecture.common

import org.springframework.http.HttpStatus

data class ApiError(
    val code: String,
    val message: String,
    val requestId: String,
)

object ErrorCodes {
    const val VALIDATION_ERROR = "VALIDATION_ERROR"
    const val UNAUTHENTICATED = "UNAUTHENTICATED"
    const val INVALID_REFRESH_TOKEN = "INVALID_REFRESH_TOKEN"
    const val NOTE_NOT_FOUND = "NOTE_NOT_FOUND"
    const val EMAIL_ALREADY_USED = "EMAIL_ALREADY_USED"
}

class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
) : RuntimeException(message)
```

`GlobalExceptionHandler.kt`：

```kotlin
package com.launcharchitecture.common

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(ApiException::class)
    fun handleApi(ex: ApiException, request: HttpServletRequest): ResponseEntity<ApiError> =
        ResponseEntity.status(ex.status).body(ApiError(ex.code, ex.message, requestId(request)))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(
        ex: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> {
        val message = ex.bindingResult.fieldErrors.joinToString("; ") { error ->
            "${error.field} ${error.defaultMessage ?: "不合法"}"
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiError(ErrorCodes.VALIDATION_ERROR, message, requestId(request)))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(
        ex: HttpMessageNotReadableException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiError(ErrorCodes.VALIDATION_ERROR, "请求格式不正确", requestId(request)))

    private fun requestId(request: HttpServletRequest): String =
        request.getAttribute(RequestIdFilter.REQUEST_ID)?.toString()
            ?: request.getHeader(RequestIdFilter.HEADER)
            ?: "unknown"
}
```

`ApiException` 与 `ErrorCodes` 放在 `ApiError.kt`。

- [ ] **Step 4: 确认测试通过**

Run: `.\gradlew.bat test --tests com.launcharchitecture.health.HealthIntegrationTest --tests com.launcharchitecture.common.GlobalExceptionHandlerTest`

Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: Commit**

在仓库根目录：

```bash
git add server/src
git commit -m "feat: add request id and API error envelope"
```

---

### Task 3: OpenAPI 契约

**Files:**
- Create: `contracts/openapi.yaml`
- Create: `server/src/test/kotlin/com/launcharchitecture/contract/OpenApiContractTest.kt`
- Test: `server/src/test/kotlin/com/launcharchitecture/contract/OpenApiContractTest.kt`

**Interfaces:**
- Consumes: 设计文档中的路径、状态码和字段名
- Produces: 仓库根目录相对路径 `contracts/openapi.yaml`。后续客户端只读这份文件，不从服务端生成代码。

- [ ] **Step 1: 写失败测试**

`OpenApiContractTest.kt`：

```kotlin
package com.launcharchitecture.contract

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class OpenApiContractTest {
    @Test
    fun contractListsTheVerticalSlice() {
        val text = Files.readString(Path.of("..", "contracts", "openapi.yaml"))
        listOf(
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/api/v1/me",
            "/api/v1/notes",
            "/api/v1/notes/{id}",
            "/api/v1/health",
            "accessToken",
            "refreshToken",
            "expiresIn",
            "displayName",
            "createdAt",
            "updatedAt",
            "X-Request-Id",
            "VALIDATION_ERROR",
            "UNAUTHENTICATED",
            "INVALID_REFRESH_TOKEN",
            "NOTE_NOT_FOUND",
            "EMAIL_ALREADY_USED",
        ).forEach { required ->
            assertTrue(text.contains(required), "missing $required")
        }
    }
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `.\gradlew.bat test --tests com.launcharchitecture.contract.OpenApiContractTest`

Expected: FAIL，找不到 `contracts/openapi.yaml`。

- [ ] **Step 3: 写契约**

`contracts/openapi.yaml`：

```yaml
openapi: 3.0.3
info:
  title: LaunchArchitecture API
  version: 0.1.0
servers:
  - url: http://localhost:8080
paths:
  /api/v1/health:
    get:
      operationId: health
      security: []
      responses:
        "200":
          description: 服务已启动
          headers:
            X-Request-Id:
              schema: { type: string }
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/HealthResponse"
  /api/v1/auth/register:
    post:
      operationId: register
      security: []
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: "#/components/schemas/RegisterRequest" }
      responses:
        "200":
          description: 注册并登录
          content:
            application/json:
              schema: { $ref: "#/components/schemas/TokenResponse" }
        "400": { $ref: "#/components/responses/ValidationError" }
        "409": { $ref: "#/components/responses/EmailUsed" }
  /api/v1/auth/login:
    post:
      operationId: login
      security: []
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: "#/components/schemas/LoginRequest" }
      responses:
        "200":
          description: 登录
          content:
            application/json:
              schema: { $ref: "#/components/schemas/TokenResponse" }
        "400": { $ref: "#/components/responses/ValidationError" }
        "401": { $ref: "#/components/responses/Unauthenticated" }
  /api/v1/auth/refresh:
    post:
      operationId: refresh
      security: []
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: "#/components/schemas/RefreshRequest" }
      responses:
        "200":
          description: 轮换刷新令牌
          content:
            application/json:
              schema: { $ref: "#/components/schemas/TokenResponse" }
        "401": { $ref: "#/components/responses/InvalidRefreshToken" }
  /api/v1/auth/logout:
    post:
      operationId: logout
      security: []
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: "#/components/schemas/RefreshRequest" }
      responses:
        "204":
          description: 已退出
        "401": { $ref: "#/components/responses/InvalidRefreshToken" }
  /api/v1/me:
    get:
      operationId: currentUser
      security: [{ bearerAuth: [] }]
      responses:
        "200":
          description: 当前用户
          content:
            application/json:
              schema: { $ref: "#/components/schemas/UserResponse" }
        "401": { $ref: "#/components/responses/Unauthenticated" }
  /api/v1/notes:
    get:
      operationId: listNotes
      security: [{ bearerAuth: [] }]
      responses:
        "200":
          description: 当前用户最近更新的最多 100 条笔记
          content:
            application/json:
              schema:
                type: array
                maxItems: 100
                items: { $ref: "#/components/schemas/NoteResponse" }
        "401": { $ref: "#/components/responses/Unauthenticated" }
    post:
      operationId: createNote
      security: [{ bearerAuth: [] }]
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: "#/components/schemas/NoteRequest" }
      responses:
        "201":
          description: 已创建
          content:
            application/json:
              schema: { $ref: "#/components/schemas/NoteResponse" }
        "400": { $ref: "#/components/responses/ValidationError" }
        "401": { $ref: "#/components/responses/Unauthenticated" }
  /api/v1/notes/{id}:
    parameters:
      - $ref: "#/components/parameters/NoteId"
    get:
      operationId: getNote
      security: [{ bearerAuth: [] }]
      responses:
        "200":
          description: 笔记
          content:
            application/json:
              schema: { $ref: "#/components/schemas/NoteResponse" }
        "401": { $ref: "#/components/responses/Unauthenticated" }
        "404": { $ref: "#/components/responses/NoteNotFound" }
    put:
      operationId: updateNote
      security: [{ bearerAuth: [] }]
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: "#/components/schemas/NoteRequest" }
      responses:
        "200":
          description: 已替换标题和正文
          content:
            application/json:
              schema: { $ref: "#/components/schemas/NoteResponse" }
        "400": { $ref: "#/components/responses/ValidationError" }
        "401": { $ref: "#/components/responses/Unauthenticated" }
        "404": { $ref: "#/components/responses/NoteNotFound" }
    delete:
      operationId: deleteNote
      security: [{ bearerAuth: [] }]
      responses:
        "204": { description: 已删除 }
        "401": { $ref: "#/components/responses/Unauthenticated" }
        "404": { $ref: "#/components/responses/NoteNotFound" }
components:
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
      bearerFormat: JWT
  parameters:
    NoteId:
      name: id
      in: path
      required: true
      schema: { type: string, format: uuid }
  responses:
    ValidationError:
      description: 校验失败
      content:
        application/json:
          schema: { $ref: "#/components/schemas/ApiError" }
          example: { code: VALIDATION_ERROR, message: email 必须是邮箱, requestId: req-1 }
    Unauthenticated:
      description: 未登录或访问令牌无效
      content:
        application/json:
          schema: { $ref: "#/components/schemas/ApiError" }
          example: { code: UNAUTHENTICATED, message: 未登录, requestId: req-1 }
    InvalidRefreshToken:
      description: 刷新令牌无效
      content:
        application/json:
          schema: { $ref: "#/components/schemas/ApiError" }
          example: { code: INVALID_REFRESH_TOKEN, message: 刷新令牌无效, requestId: req-1 }
    NoteNotFound:
      description: 笔记不存在或不属于当前用户
      content:
        application/json:
          schema: { $ref: "#/components/schemas/ApiError" }
          example: { code: NOTE_NOT_FOUND, message: 笔记不存在, requestId: req-1 }
    EmailUsed:
      description: 邮箱已被注册
      content:
        application/json:
          schema: { $ref: "#/components/schemas/ApiError" }
          example: { code: EMAIL_ALREADY_USED, message: 邮箱已被注册, requestId: req-1 }
  schemas:
    ApiError:
      type: object
      required: [code, message, requestId]
      properties:
        code: { type: string }
        message: { type: string }
        requestId: { type: string }
    HealthResponse:
      type: object
      required: [status]
      properties:
        status: { type: string, enum: [up] }
    RegisterRequest:
      type: object
      required: [email, password, displayName]
      properties:
        email: { type: string, format: email }
        password: { type: string, minLength: 8, maxLength: 72 }
        displayName: { type: string, minLength: 1, maxLength: 40 }
    LoginRequest:
      type: object
      required: [email, password]
      properties:
        email: { type: string, format: email }
        password: { type: string }
    RefreshRequest:
      type: object
      required: [refreshToken]
      properties:
        refreshToken: { type: string }
    TokenResponse:
      type: object
      required: [accessToken, refreshToken, expiresIn]
      properties:
        accessToken: { type: string }
        refreshToken: { type: string }
        expiresIn: { type: integer, enum: [900] }
    UserResponse:
      type: object
      required: [id, email, displayName]
      properties:
        id: { type: string, format: uuid }
        email: { type: string, format: email }
        displayName: { type: string }
    NoteRequest:
      type: object
      required: [title]
      properties:
        title: { type: string, minLength: 1, maxLength: 80 }
        body: { type: string, maxLength: 4000, default: "" }
    NoteResponse:
      type: object
      required: [id, title, body, createdAt, updatedAt]
      properties:
        id: { type: string, format: uuid }
        title: { type: string }
        body: { type: string }
        createdAt: { type: string, format: date-time }
        updatedAt: { type: string, format: date-time }
```

- [ ] **Step 4: 确认测试通过**

Run: `.\gradlew.bat test --tests com.launcharchitecture.contract.OpenApiContractTest`

Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: Commit**

在仓库根目录：

```bash
git add contracts/openapi.yaml server/src/test/kotlin/com/launcharchitecture/contract
git commit -m "docs: add the OpenAPI contract for the vertical slice"
```

---

### Task 4: 表结构、用户和刷新令牌存储

**Files:**
- Modify: `server/build.gradle.kts`
- Create: `server/src/main/resources/db/migration/V1__init.sql`
- Modify: `server/src/main/resources/application.yml`
- Create: `server/src/test/resources/application.yml`
- Create: `server/src/main/kotlin/com/launcharchitecture/user/UserAccount.kt`
- Create: `server/src/main/kotlin/com/launcharchitecture/user/UserRepository.kt`
- Create: `server/src/main/kotlin/com/launcharchitecture/auth/RefreshToken.kt`
- Create: `server/src/main/kotlin/com/launcharchitecture/auth/RefreshTokenRepository.kt`
- Create: `server/src/test/kotlin/com/launcharchitecture/user/UserRepositoryTest.kt`
- Test: `server/src/test/kotlin/com/launcharchitecture/user/UserRepositoryTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `UserAccount(id: UUID, email: String, passwordHash: String, displayName: String, createdAt: Instant)`，表 `users`
  - `UserRepository.findByEmail(email: String): UserAccount?`
  - `UserRepository.existsByEmail(email: String): Boolean`
  - `RefreshToken(id: UUID, userId: UUID, tokenHash: String, expiresAt: Instant, revokedAt: Instant?)`，表 `refresh_tokens`
  - `RefreshTokenRepository.findByTokenHash(tokenHash: String): RefreshToken?`

- [ ] **Step 1: 写失败测试**

`UserRepositoryTest.kt`：

```kotlin
package com.launcharchitecture.user

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import java.util.UUID

@SpringBootTest
class UserRepositoryTest(
    @Autowired val users: UserRepository,
) {
    @Test
    fun savesAndFindsByEmail() {
        val email = "user-${UUID.randomUUID()}@example.com"
        users.save(UserAccount(email = email, passwordHash = "hash", displayName = "Ada"))
        val found = users.findByEmail(email)
        assertEquals("Ada", found!!.displayName)
        assertTrue(users.existsByEmail(email))
    }

    @Test
    fun rejectsDuplicateEmail() {
        val email = "dup-${UUID.randomUUID()}@example.com"
        users.saveAndFlush(UserAccount(email = email, passwordHash = "hash", displayName = "Ada"))
        org.junit.jupiter.api.Assertions.assertThrows(DataIntegrityViolationException::class.java) {
            users.saveAndFlush(UserAccount(email = email, passwordHash = "other", displayName = "Bea"))
        }
    }
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `.\gradlew.bat test --tests com.launcharchitecture.user.UserRepositoryTest`

Expected: FAIL，`UserRepository` 不存在。

- [ ] **Step 3: 实现迁移和仓库**

把 `server/build.gradle.kts` 的 `plugins` 和 `dependencies` 换成：

```kotlin
plugins {
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    kotlin("plugin.jpa") version "1.9.25"
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.flywaydb:flyway-database-h2:11.7.2")
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
```

其余 `group`、`java`、`repositories`、`kotlin`、`tasks.withType<Test>` 保持 Task 1 的内容。

`V1__init.sql`：

```sql
CREATE TABLE users (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(72) NOT NULL,
    display_name VARCHAR(40) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX users_email_key ON users (email);

CREATE TABLE refresh_tokens (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP NULL,
    CONSTRAINT refresh_tokens_user_fk FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE UNIQUE INDEX refresh_tokens_hash_key ON refresh_tokens (token_hash);

CREATE TABLE notes (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    title VARCHAR(80) NOT NULL,
    body VARCHAR(4000) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT notes_user_fk FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX notes_user_updated_idx ON notes (user_id, updated_at);
```

把 `server/src/main/resources/application.yml` 换成：

```yaml
server:
  port: 8080
spring:
  jackson:
    serialization:
      write-dates-as-timestamps: false
    time-zone: UTC
  datasource:
    url: jdbc:h2:file:./data/launcharchitecture;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH
    driver-class-name: org.h2.Driver
    username: sa
    password:
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        jdbc:
          time_zone: UTC
  flyway:
    enabled: true
app:
  jwt:
    secret: ${JWT_SECRET:local-only-development-secret-key-32b}
```

`server/src/test/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:launcharchitecture;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: sa
    password:
app:
  jwt:
    secret: local-only-development-secret-key-32b
```

`UserAccount.kt`：

```kotlin
package com.launcharchitecture.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
class UserAccount(
    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false, unique = true, length = 320)
    var email: String = "",

    @Column(name = "password_hash", nullable = false, length = 72)
    var passwordHash: String = "",

    @Column(name = "display_name", nullable = false, length = 40)
    var displayName: String = "",

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
```

`UserRepository.kt`：

```kotlin
package com.launcharchitecture.user

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserRepository : JpaRepository<UserAccount, UUID> {
    fun findByEmail(email: String): UserAccount?
    fun existsByEmail(email: String): Boolean
}
```

`RefreshToken.kt`：

```kotlin
package com.launcharchitecture.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "refresh_tokens")
class RefreshToken(
    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    val id: UUID = UUID.randomUUID(),

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", nullable = false, length = 36)
    val userId: UUID = UUID.randomUUID(),

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    val tokenHash: String = "",

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant = Instant.now(),

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,
)
```

`RefreshTokenRepository.kt`：

```kotlin
package com.launcharchitecture.auth

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface RefreshTokenRepository : JpaRepository<RefreshToken, UUID> {
    fun findByTokenHash(tokenHash: String): RefreshToken?
}
```

- [ ] **Step 4: 确认测试通过**

Run: `.\gradlew.bat test --tests com.launcharchitecture.user.UserRepositoryTest --tests com.launcharchitecture.health.HealthIntegrationTest`

Expected: BUILD SUCCESSFUL。若失败信息是 `Schema-validation: wrong column type`，把该字段的 `@JdbcTypeCode(SqlTypes.TIMESTAMP)` 换成 `@Column(..., columnDefinition = "timestamp")` 并删掉对应的 `@JdbcTypeCode`。不要把 `ddl-auto` 改成 `update`。

- [ ] **Step 5: Commit**

在仓库根目录：

```bash
git add server
git commit -m "feat: add user and refresh-token schema"
```

---

### Task 5: 注册并读取当前用户

**Files:**
- Modify: `server/build.gradle.kts`
- Create: `server/src/main/kotlin/com/launcharchitecture/config/AppProperties.kt`
- Create: `server/src/main/kotlin/com/launcharchitecture/config/AccessTokenIssuer.kt`
- Create: `server/src/main/kotlin/com/launcharchitecture/config/SecurityConfig.kt`
- Create: `server/src/main/kotlin/com/launcharchitecture/auth/RefreshTokenGenerator.kt`
- Create: `server/src/main/kotlin/com/launcharchitecture/auth/AuthRequests.kt`
- Create: `server/src/main/kotlin/com/launcharchitecture/auth/AuthService.kt`
- Create: `server/src/main/kotlin/com/launcharchitecture/auth/AuthController.kt`
- Create: `server/src/main/kotlin/com/launcharchitecture/auth/CurrentUser.kt`
- Create: `server/src/main/kotlin/com/launcharchitecture/user/UserController.kt`
- Modify: `server/src/main/kotlin/com/launcharchitecture/LaunchArchitectureApplication.kt`
- Create: `server/src/test/kotlin/com/launcharchitecture/auth/ApiTestClient.kt`
- Create: `server/src/test/kotlin/com/launcharchitecture/auth/RegisterIntegrationTest.kt`
- Test: `server/src/test/kotlin/com/launcharchitecture/auth/RegisterIntegrationTest.kt`

**Interfaces:**
- Consumes: `UserRepository`、`RefreshTokenRepository`、`ApiException`、`ErrorCodes`、`RequestIdFilter`
- Produces:
  - `TokenResponse(accessToken: String, refreshToken: String, expiresIn: Long)`，`expiresIn` 为 900
  - `RegisterRequest(email: String, password: String, displayName: String)`
  - `UserResponse(id: UUID, email: String, displayName: String)`
  - `AuthService.register(request: RegisterRequest): TokenResponse`
  - `CurrentUser.id(): UUID`，读取 JWT 的 `sub`
  - `AccessTokenIssuer.issue(userId: UUID): String`
  - `RefreshTokenGenerator.generate(): String` 与 `hash(raw: String): String`，哈希为 SHA-256 的 64 位十六进制
  - `ApiTestClient.register(email: String, password: String = "password12", displayName: String = "Ada"): TokenResponse`

- [ ] **Step 1: 写失败测试**

`ApiTestClient.kt`：

```kotlin
package com.launcharchitecture.auth

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

class ApiTestClient(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
) {
    fun register(
        email: String,
        password: String = "password12",
        displayName: String = "Ada",
    ): TokenResponse {
        val body = mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                mapOf("email" to email, "password" to password, "displayName" to displayName),
            )
        }.andExpect {
            status { isOk() }
        }.andReturn().response.contentAsString
        return objectMapper.readValue(body, TokenResponse::class.java)
    }
}
```

`RegisterIntegrationTest.kt`：

```kotlin
package com.launcharchitecture.auth

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
class RegisterIntegrationTest(
    @Autowired val mockMvc: MockMvc,
    @Autowired val objectMapper: ObjectMapper,
) {
    private val client by lazy { ApiTestClient(mockMvc, objectMapper) }

    @Test
    fun registerReturnsTokensAndCurrentUser() {
        val email = "Ada-${UUID.randomUUID()}@Example.com"
        val tokens = client.register(email)
        assertEquals(900, tokens.expiresIn)
        mockMvc.get("/api/v1/me") {
            header("Authorization", "Bearer ${tokens.accessToken}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.email") { value(email.trim().lowercase()) }
            jsonPath("$.displayName") { value("Ada") }
        }
    }

    @Test
    fun duplicateEmailReturns409() {
        val email = "dup-${UUID.randomUUID()}@example.com"
        client.register(email)
        mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"password12","displayName":"Bea"}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("EMAIL_ALREADY_USED") }
        }
    }

    @Test
    fun shortPasswordReturns400() {
        mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"short-${UUID.randomUUID()}@example.com","password":"short","displayName":"Ada"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
            jsonPath("$.requestId") { exists() }
        }
    }
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `.\gradlew.bat test --tests com.launcharchitecture.auth.RegisterIntegrationTest`

Expected: FAIL，注册接口不存在，状态不是 200。

- [ ] **Step 3: 实现注册、JWT 和当前用户**

在 `dependencies` 增加：

```kotlin
implementation("org.springframework.boot:spring-boot-starter-security")
implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
implementation("com.nimbusds:nimbus-jose-jwt")
testImplementation("org.springframework.security:spring-security-test")
```

`LaunchArchitectureApplication.kt` 增加配置绑定：

```kotlin
package com.launcharchitecture

import com.launcharchitecture.config.AppProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(AppProperties::class)
class LaunchArchitectureApplication

fun main(args: Array<String>) {
    runApplication<LaunchArchitectureApplication>(*args)
}
```

`AppProperties.kt`：

```kotlin
package com.launcharchitecture.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val jwt: Jwt = Jwt(),
) {
    data class Jwt(
        val secret: String = "",
    )
}
```

`AccessTokenIssuer.kt`：

```kotlin
package com.launcharchitecture.config

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Date
import java.util.UUID

@Component
class AccessTokenIssuer(private val properties: AppProperties) {
    fun issue(userId: UUID): String {
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .subject(userId.toString())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(900)))
            .build()
        val signed = SignedJWT(JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims)
        signed.sign(MACSigner(properties.jwt.secret.toByteArray(Charsets.UTF_8)))
        return signed.serialize()
    }
}
```

`SecurityConfig.kt`：

```kotlin
package com.launcharchitecture.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.launcharchitecture.common.ApiError
import com.launcharchitecture.common.ErrorCodes
import com.launcharchitecture.common.RequestIdFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.AuthenticationEntryPoint
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

@Configuration
class SecurityConfig(private val properties: AppProperties) {
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun jwtDecoder(): JwtDecoder {
        val key = SecretKeySpec(properties.jwt.secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        return NimbusJwtDecoder.withSecretKey(key).build()
    }

    @Bean
    fun authenticationEntryPoint(objectMapper: ObjectMapper): AuthenticationEntryPoint =
        AuthenticationEntryPoint { request, response, _ ->
            writeUnauthenticated(objectMapper, request, response)
        }

    @Bean
    fun securityFilterChain(http: HttpSecurity, entryPoint: AuthenticationEntryPoint): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(
                    "/api/v1/health",
                    "/api/v1/auth/register",
                    "/api/v1/auth/login",
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/logout",
                ).permitAll()
                auth.anyRequest().authenticated()
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt(Customizer.withDefaults())
                oauth2.authenticationEntryPoint(entryPoint)
            }
        return http.build()
    }

    private fun writeUnauthenticated(
        objectMapper: ObjectMapper,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val requestId = request.getAttribute(RequestIdFilter.REQUEST_ID)?.toString()
            ?: UUID.randomUUID().toString()
        response.status = 401
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.setHeader(RequestIdFilter.HEADER, requestId)
        objectMapper.writeValue(
            response.writer,
            ApiError(ErrorCodes.UNAUTHENTICATED, "未登录", requestId),
        )
    }
}
```

`RefreshTokenGenerator.kt`：

```kotlin
package com.launcharchitecture.auth

import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

@Component
class RefreshTokenGenerator {
    private val random = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hash(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
```

`AuthRequests.kt`：

```kotlin
package com.launcharchitecture.auth

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

data class RegisterRequest(
    @field:NotBlank(message = "不能为空")
    @field:Email(message = "必须是邮箱")
    val email: String,
    @field:NotBlank(message = "不能为空")
    @field:Size(min = 8, max = 72, message = "长度必须在 8 到 72 之间")
    val password: String,
    @field:NotBlank(message = "不能为空")
    @field:Size(min = 1, max = 40, message = "长度必须在 1 到 40 之间")
    val displayName: String,
)

data class LoginRequest(
    @field:NotBlank(message = "不能为空")
    @field:Email(message = "必须是邮箱")
    val email: String,
    @field:NotBlank(message = "不能为空")
    val password: String,
)

data class RefreshRequest(
    @field:NotBlank(message = "不能为空")
    val refreshToken: String,
)

data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long = 900,
)

data class UserResponse(
    val id: UUID,
    val email: String,
    val displayName: String,
)
```

`CurrentUser.kt`：

```kotlin
package com.launcharchitecture.auth

import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

object CurrentUser {
    fun id(): UUID = UUID.fromString(SecurityContextHolder.getContext().authentication.name)
}
```

`AuthService.kt`：

```kotlin
package com.launcharchitecture.auth

import com.launcharchitecture.common.ApiException
import com.launcharchitecture.common.ErrorCodes
import com.launcharchitecture.config.AccessTokenIssuer
import com.launcharchitecture.user.UserAccount
import com.launcharchitecture.user.UserRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class AuthService(
    private val users: UserRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val passwordEncoder: PasswordEncoder,
    private val accessTokens: AccessTokenIssuer,
    private val refreshTokenGenerator: RefreshTokenGenerator,
) {
    @Transactional
    fun register(request: RegisterRequest): TokenResponse {
        val email = request.email.trim().lowercase()
        if (users.existsByEmail(email)) {
            throw ApiException(HttpStatus.CONFLICT, ErrorCodes.EMAIL_ALREADY_USED, "邮箱已被注册")
        }
        val user = UserAccount(
            email = email,
            passwordHash = passwordEncoder.encode(request.password),
            displayName = request.displayName,
        )
        try {
            users.saveAndFlush(user)
        } catch (ex: DataIntegrityViolationException) {
            throw ApiException(HttpStatus.CONFLICT, ErrorCodes.EMAIL_ALREADY_USED, "邮箱已被注册")
        }
        return issueTokens(user)
    }

    @Transactional
    fun issueTokens(user: UserAccount): TokenResponse {
        val raw = refreshTokenGenerator.generate()
        refreshTokens.save(
            RefreshToken(
                userId = user.id,
                tokenHash = refreshTokenGenerator.hash(raw),
                expiresAt = Instant.now().plus(14, ChronoUnit.DAYS),
            ),
        )
        return TokenResponse(
            accessToken = accessTokens.issue(user.id),
            refreshToken = raw,
            expiresIn = 900,
        )
    }
}
```

`AuthController.kt`：

```kotlin
package com.launcharchitecture.auth

import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(private val authService: AuthService) {
    @PostMapping("/register")
    fun register(@Valid @RequestBody request: RegisterRequest): TokenResponse = authService.register(request)
}
```

`UserController.kt`：

```kotlin
package com.launcharchitecture.user

import com.launcharchitecture.auth.CurrentUser
import com.launcharchitecture.auth.UserResponse
import com.launcharchitecture.common.ApiException
import com.launcharchitecture.common.ErrorCodes
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class UserController(private val users: UserRepository) {
    @GetMapping("/me")
    fun me(): UserResponse {
        val user = users.findById(CurrentUser.id()).orElseThrow {
            ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHENTICATED, "未登录")
        }
        return UserResponse(id = user.id, email = user.email, displayName = user.displayName)
    }
}
```

- [ ] **Step 4: 确认测试通过**

Run: `.\gradlew.bat test --tests com.launcharchitecture.auth.RegisterIntegrationTest --tests com.launcharchitecture.health.HealthIntegrationTest`

Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: Commit**

在仓库根目录：

```bash
git add server
git commit -m "feat: register a user and return the current profile"
```

---

### Task 6: 登录和访问令牌失败

**Files:**
- Modify: `server/src/main/kotlin/com/launcharchitecture/auth/AuthService.kt`
- Modify: `server/src/main/kotlin/com/launcharchitecture/auth/AuthController.kt`
- Create: `server/src/test/kotlin/com/launcharchitecture/auth/LoginIntegrationTest.kt`
- Test: `server/src/test/kotlin/com/launcharchitecture/auth/LoginIntegrationTest.kt`

**Interfaces:**
- Consumes: `AuthService.issueTokens(user: UserAccount): TokenResponse`、`ApiTestClient.register`、`PasswordEncoder`、本地测试密钥 `local-only-development-secret-key-32b`
- Produces: `AuthService.login(request: LoginRequest): TokenResponse`。邮箱不存在或密码错误都返回 401 `UNAUTHENTICATED`，消息为 `邮箱或密码不正确`。

- [ ] **Step 1: 写失败测试**

`LoginIntegrationTest.kt`：

```kotlin
package com.launcharchitecture.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.Date
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
class LoginIntegrationTest(
    @Autowired val mockMvc: MockMvc,
    @Autowired val objectMapper: ObjectMapper,
) {
    private val client by lazy { ApiTestClient(mockMvc, objectMapper) }

    @Test
    fun loginWithNormalizedEmail() {
        val email = "Login-${UUID.randomUUID()}@Example.com"
        client.register(email, password = "password12")
        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"${email.lowercase()}","password":"password12"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.expiresIn") { value(900) }
            jsonPath("$.accessToken") { exists() }
        }
    }

    @Test
    fun wrongPasswordIsUnauthenticated() {
        val email = "bad-${UUID.randomUUID()}@example.com"
        client.register(email)
        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"wrong-password"}"""
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
        }
    }

    @Test
    fun missingAccessTokenIsUnauthenticated() {
        mockMvc.get("/api/v1/me").andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
            header { exists("X-Request-Id") }
        }
    }

    @Test
    fun expiredAccessTokenIsUnauthenticated() {
        val expired = expiredToken(UUID.randomUUID())
        mockMvc.get("/api/v1/me") {
            header("Authorization", "Bearer $expired")
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
        }
    }

    private fun expiredToken(userId: UUID): String {
        val claims = JWTClaimsSet.Builder()
            .subject(userId.toString())
            .expirationTime(Date.from(Instant.now().minusSeconds(60)))
            .build()
        val signed = SignedJWT(JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims)
        signed.sign(MACSigner("local-only-development-secret-key-32b".toByteArray(Charsets.UTF_8)))
        return signed.serialize()
    }
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `.\gradlew.bat test --tests com.launcharchitecture.auth.LoginIntegrationTest`

Expected: FAIL，`/api/v1/auth/login` 不是 200。缺少令牌的用例此时可以已经是 401。

- [ ] **Step 3: 实现登录**

在 `AuthService` 中、`register` 之后加入：

```kotlin
@Transactional
fun login(request: LoginRequest): TokenResponse {
    val email = request.email.trim().lowercase()
    val user = users.findByEmail(email)
    if (user == null || !passwordEncoder.matches(request.password, user.passwordHash)) {
        throw ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHENTICATED, "邮箱或密码不正确")
    }
    return issueTokens(user)
}
```

在 `AuthController` 中加入：

```kotlin
@PostMapping("/login")
fun login(@Valid @RequestBody request: LoginRequest): TokenResponse = authService.login(request)
```

- [ ] **Step 4: 确认测试通过**

Run: `.\gradlew.bat test --tests com.launcharchitecture.auth.LoginIntegrationTest --tests com.launcharchitecture.auth.RegisterIntegrationTest`

Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: Commit**

在仓库根目录：

```bash
git add server/src/main/kotlin/com/launcharchitecture/auth server/src/test/kotlin/com/launcharchitecture/auth/LoginIntegrationTest.kt
git commit -m "feat: log in with email and password"
```

---

### Task 7: 刷新和退出

**Files:**
- Modify: `server/src/main/kotlin/com/launcharchitecture/auth/AuthService.kt`
- Modify: `server/src/main/kotlin/com/launcharchitecture/auth/AuthController.kt`
- Create: `server/src/test/kotlin/com/launcharchitecture/auth/RefreshIntegrationTest.kt`
- Test: `server/src/test/kotlin/com/launcharchitecture/auth/RefreshIntegrationTest.kt`

**Interfaces:**
- Consumes: `RefreshTokenRepository.findByTokenHash`、`RefreshTokenGenerator.hash`、`AuthService.issueTokens`、`UserRepository.findById`
- Produces:
  - `AuthService.refresh(request: RefreshRequest): TokenResponse`
  - `AuthService.logout(request: RefreshRequest)`
  - 无效、过期或已作废的刷新令牌抛出 `ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.INVALID_REFRESH_TOKEN, "刷新令牌无效")`

- [ ] **Step 1: 写失败测试**

`RefreshIntegrationTest.kt`：

```kotlin
package com.launcharchitecture.auth

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
class RefreshIntegrationTest(
    @Autowired val mockMvc: MockMvc,
    @Autowired val objectMapper: ObjectMapper,
) {
    private val client by lazy { ApiTestClient(mockMvc, objectMapper) }

    @Test
    fun refreshRotatesTokenAndRejectsTheOldOne() {
        val tokens = client.register("refresh-${UUID.randomUUID()}@example.com")
        val refreshed = refreshOk(tokens.refreshToken)
        val next = objectMapper.readValue(refreshed, TokenResponse::class.java)
        assertNotEquals(tokens.refreshToken, next.refreshToken)
        mockMvc.get("/api/v1/me") {
            header("Authorization", "Bearer ${next.accessToken}")
        }.andExpect { status { isOk() } }
        refreshUnauthorized(tokens.refreshToken)
    }

    @Test
    fun invalidRefreshTokenReturns401() {
        mockMvc.post("/api/v1/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"refreshToken":"not-a-real-token"}"""
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("INVALID_REFRESH_TOKEN") }
        }
    }

    @Test
    fun logoutRevokesRefreshToken() {
        val tokens = client.register("logout-${UUID.randomUUID()}@example.com")
        mockMvc.post("/api/v1/auth/logout") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"refreshToken":"${tokens.refreshToken}"}"""
        }.andExpect { status { isNoContent() } }
        refreshUnauthorized(tokens.refreshToken)
    }

    private fun refreshOk(refreshToken: String): String =
        mockMvc.post("/api/v1/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"refreshToken":"$refreshToken"}"""
        }.andExpect {
            status { isOk() }
        }.andReturn().response.contentAsString

    private fun refreshUnauthorized(refreshToken: String) {
        mockMvc.post("/api/v1/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"refreshToken":"$refreshToken"}"""
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("INVALID_REFRESH_TOKEN") }
        }
    }
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `.\gradlew.bat test --tests com.launcharchitecture.auth.RefreshIntegrationTest`

Expected: FAIL，刷新或退出的状态不符合断言。

- [ ] **Step 3: 实现轮换和作废**

在 `AuthService` 增加：

```kotlin
@Transactional
fun refresh(request: RefreshRequest): TokenResponse {
    val existing = usableToken(request.refreshToken)
    existing.revokedAt = Instant.now()
    refreshTokens.save(existing)
    val user = users.findById(existing.userId).orElseThrow { invalidRefresh() }
    return issueTokens(user)
}

@Transactional
fun logout(request: RefreshRequest) {
    val existing = usableToken(request.refreshToken)
    existing.revokedAt = Instant.now()
    refreshTokens.save(existing)
}

private fun usableToken(raw: String): RefreshToken {
    val existing = refreshTokens.findByTokenHash(refreshTokenGenerator.hash(raw)) ?: throw invalidRefresh()
    if (existing.revokedAt != null || !existing.expiresAt.isAfter(Instant.now())) {
        throw invalidRefresh()
    }
    return existing
}

private fun invalidRefresh(): ApiException =
    ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.INVALID_REFRESH_TOKEN, "刷新令牌无效")
```

在 `AuthController` 增加：

```kotlin
@PostMapping("/refresh")
fun refresh(@Valid @RequestBody request: RefreshRequest): TokenResponse = authService.refresh(request)

@PostMapping("/logout")
@ResponseStatus(HttpStatus.NO_CONTENT)
fun logout(@Valid @RequestBody request: RefreshRequest) {
    authService.logout(request)
}
```

补上 `org.springframework.http.HttpStatus` 与 `org.springframework.web.bind.annotation.ResponseStatus` 的导入。

- [ ] **Step 4: 确认测试通过**

Run: `.\gradlew.bat test --tests com.launcharchitecture.auth.RefreshIntegrationTest`

Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: Commit**

在仓库根目录：

```bash
git add server/src/main/kotlin/com/launcharchitecture/auth server/src/test/kotlin/com/launcharchitecture/auth/RefreshIntegrationTest.kt
git commit -m "feat: rotate and revoke refresh tokens"
```

---

### Task 8: 笔记模块

**Files:**
- Create: `server/src/main/kotlin/com/launcharchitecture/note/Note.kt`
- Create: `server/src/main/kotlin/com/launcharchitecture/note/NoteRepository.kt`
- Create: `server/src/main/kotlin/com/launcharchitecture/note/NoteService.kt`
- Create: `server/src/main/kotlin/com/launcharchitecture/note/NoteController.kt`
- Create: `server/src/test/kotlin/com/launcharchitecture/note/NoteIntegrationTest.kt`
- Test: `server/src/test/kotlin/com/launcharchitecture/note/NoteIntegrationTest.kt`

**Interfaces:**
- Consumes: `CurrentUser.id(): UUID`、`ApiTestClient.register`、`ErrorCodes.NOTE_NOT_FOUND`
- Produces:
  - `NoteRequest(title: String, body: String? = null)`
  - `NoteResponse(id: UUID, title: String, body: String, createdAt: Instant, updatedAt: Instant)`
  - `NoteRepository.findByIdAndUserId(id: UUID, userId: UUID): Note?`
  - `NoteRepository.findTop100ByUserIdOrderByUpdatedAtDesc(userId: UUID): List<Note>`
  - `NoteService.list(): List<NoteResponse>`
  - `NoteService.create(request: NoteRequest): NoteResponse`
  - `NoteService.get(id: UUID): NoteResponse`
  - `NoteService.update(id: UUID, request: NoteRequest): NoteResponse`
  - `NoteService.delete(id: UUID)`

- [ ] **Step 1: 写失败测试**

`NoteIntegrationTest.kt`：

```kotlin
package com.launcharchitecture.note

import com.fasterxml.jackson.databind.ObjectMapper
import com.launcharchitecture.auth.ApiTestClient
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.Instant
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
class NoteIntegrationTest(
    @Autowired val mockMvc: MockMvc,
    @Autowired val objectMapper: ObjectMapper,
    @Autowired val notes: NoteRepository,
    @Autowired val users: com.launcharchitecture.user.UserRepository,
) {
    private val client by lazy { ApiTestClient(mockMvc, objectMapper) }

    @Test
    fun ownerCanCreateListUpdateAndDelete() {
        val tokens = client.register("notes-${UUID.randomUUID()}@example.com")
        val created = mockMvc.post("/api/v1/notes") {
            header("Authorization", "Bearer ${tokens.accessToken}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"第一篇"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.title") { value("第一篇") }
            jsonPath("$.body") { value("") }
        }.andReturn().response.contentAsString
        val note = objectMapper.readTree(created)
        val id = note.get("id").asText()

        mockMvc.get("/api/v1/notes") {
            header("Authorization", "Bearer ${tokens.accessToken}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].id") { value(id) }
        }

        mockMvc.put("/api/v1/notes/$id") {
            header("Authorization", "Bearer ${tokens.accessToken}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"改过","body":"正文"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.title") { value("改过") }
            jsonPath("$.body") { value("正文") }
        }

        mockMvc.delete("/api/v1/notes/$id") {
            header("Authorization", "Bearer ${tokens.accessToken}")
        }.andExpect { status { isNoContent() } }

        mockMvc.get("/api/v1/notes/$id") {
            header("Authorization", "Bearer ${tokens.accessToken}")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOTE_NOT_FOUND") }
        }
    }

    @Test
    fun anotherUserCannotReadTheNote() {
        val owner = client.register("owner-${UUID.randomUUID()}@example.com")
        val other = client.register("other-${UUID.randomUUID()}@example.com")
        val created = mockMvc.post("/api/v1/notes") {
            header("Authorization", "Bearer ${owner.accessToken}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"私有","body":"秘密"}"""
        }.andReturn().response.contentAsString
        val id = objectMapper.readTree(created).get("id").asText()
        mockMvc.get("/api/v1/notes/$id") {
            header("Authorization", "Bearer ${other.accessToken}")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOTE_NOT_FOUND") }
        }
    }

    @Test
    fun listReturnsTheHundredMostRecentlyUpdated() {
        val email = "many-${UUID.randomUUID()}@example.com"
        val tokens = client.register(email)
        val owner = users.findByEmail(email)!!
        val base = Instant.parse("2026-01-01T00:00:00Z")
        repeat(101) { index ->
            notes.save(
                Note(
                    userId = owner.id,
                    title = "note-$index",
                    body = "",
                    createdAt = base.plusSeconds(index.toLong()),
                    updatedAt = base.plusSeconds(index.toLong()),
                ),
            )
        }
        mockMvc.get("/api/v1/notes") {
            header("Authorization", "Bearer ${tokens.accessToken}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(100) }
            jsonPath("$[0].title") { value("note-100") }
        }
    }
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `.\gradlew.bat test --tests com.launcharchitecture.note.NoteIntegrationTest`

Expected: FAIL，`Note` 或 `/api/v1/notes` 不存在。

- [ ] **Step 3: 实现笔记模块**

`Note.kt`：

```kotlin
package com.launcharchitecture.note

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "notes")
class Note(
    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    val id: UUID = UUID.randomUUID(),

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", nullable = false, length = 36)
    val userId: UUID = UUID.randomUUID(),

    @Column(nullable = false, length = 80)
    var title: String = "",

    @Column(nullable = false, length = 4000)
    var body: String = "",

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
```

`NoteRepository.kt`：

```kotlin
package com.launcharchitecture.note

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface NoteRepository : JpaRepository<Note, UUID> {
    fun findByIdAndUserId(id: UUID, userId: UUID): Note?
    fun findTop100ByUserIdOrderByUpdatedAtDesc(userId: UUID): List<Note>
}
```

`NoteService.kt`：

```kotlin
package com.launcharchitecture.note

import com.launcharchitecture.auth.CurrentUser
import com.launcharchitecture.common.ApiException
import com.launcharchitecture.common.ErrorCodes
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class NoteRequest(
    @field:NotBlank(message = "不能为空")
    @field:Size(min = 1, max = 80, message = "长度必须在 1 到 80 之间")
    val title: String,
    @field:Size(max = 4000, message = "长度不能超过 4000")
    val body: String? = null,
)

data class NoteResponse(
    val id: UUID,
    val title: String,
    val body: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Service
class NoteService(private val notes: NoteRepository) {
    fun list(): List<NoteResponse> =
        notes.findTop100ByUserIdOrderByUpdatedAtDesc(CurrentUser.id()).map(::toResponse)

    @Transactional
    fun create(request: NoteRequest): NoteResponse {
        val now = Instant.now()
        val saved = notes.save(
            Note(
                userId = CurrentUser.id(),
                title = request.title,
                body = request.body ?: "",
                createdAt = now,
                updatedAt = now,
            ),
        )
        return toResponse(saved)
    }

    fun get(id: UUID): NoteResponse = toResponse(owned(id))

    @Transactional
    fun update(id: UUID, request: NoteRequest): NoteResponse {
        val note = owned(id)
        note.title = request.title
        note.body = request.body ?: ""
        note.updatedAt = Instant.now()
        return toResponse(notes.save(note))
    }

    @Transactional
    fun delete(id: UUID) {
        notes.delete(owned(id))
    }

    private fun owned(id: UUID): Note =
        notes.findByIdAndUserId(id, CurrentUser.id())
            ?: throw ApiException(HttpStatus.NOT_FOUND, ErrorCodes.NOTE_NOT_FOUND, "笔记不存在")

    private fun toResponse(note: Note) = NoteResponse(
        id = note.id,
        title = note.title,
        body = note.body,
        createdAt = note.createdAt,
        updatedAt = note.updatedAt,
    )
}
```

`NoteController.kt`：

```kotlin
package com.launcharchitecture.note

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/notes")
class NoteController(private val notes: NoteService) {
    @GetMapping
    fun list(): List<NoteResponse> = notes.list()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: NoteRequest): NoteResponse = notes.create(request)

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): NoteResponse = notes.get(id)

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: NoteRequest): NoteResponse =
        notes.update(id, request)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) {
        notes.delete(id)
    }
}
```

- [ ] **Step 4: 确认测试通过**

Run: `.\gradlew.bat test --tests com.launcharchitecture.note.NoteIntegrationTest`

Expected: BUILD SUCCESSFUL。`$[0].title` 为 `note-100`。若同一秒的排序不稳定，确认测试写入了递增的 `updatedAt`，查询方法名保持 `findTop100ByUserIdOrderByUpdatedAtDesc`。

- [ ] **Step 5: Commit**

在仓库根目录：

```bash
git add server/src/main/kotlin/com/launcharchitecture/note server/src/test/kotlin/com/launcharchitecture/note
git commit -m "feat: add owner-scoped notes"
```

---

### Task 9: 跨域和正式环境配置

**Files:**
- Modify: `server/src/main/kotlin/com/launcharchitecture/config/SecurityConfig.kt`
- Create: `server/src/main/resources/application-prod.yml`
- Create: `server/README.md`
- Create: `server/src/test/kotlin/com/launcharchitecture/config/CorsAndProdConfigTest.kt`
- Test: `server/src/test/kotlin/com/launcharchitecture/config/CorsAndProdConfigTest.kt`

**Interfaces:**
- Consumes: `SecurityConfig.securityFilterChain`、`GET /api/v1/health`
- Produces: 对源 `http://localhost:5173` 的 CORS，允许并暴露 `Authorization`、`Content-Type`、`X-Request-Id`。`application-prod.yml` 不含本地默认 JWT 密钥。

- [ ] **Step 1: 写失败测试**

`CorsAndProdConfigTest.kt`：

```kotlin
package com.launcharchitecture.config

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.options
import java.nio.file.Files
import java.nio.file.Path

@SpringBootTest
@AutoConfigureMockMvc
class CorsAndProdConfigTest(@Autowired val mockMvc: MockMvc) {
    @Test
    fun localWebOriginIsAllowed() {
        mockMvc.options("/api/v1/health") {
            header("Origin", "http://localhost:5173")
            header("Access-Control-Request-Method", "GET")
            header("Access-Control-Request-Headers", "Authorization, Content-Type, X-Request-Id")
        }.andExpect {
            status { isOk() }
            header { string("Access-Control-Allow-Origin", "http://localhost:5173") }
            header { string("Access-Control-Expose-Headers", "X-Request-Id") }
        }
    }

    @Test
    fun prodProfileRequiresEnvironment() {
        val text = Files.readString(Path.of("src/main/resources/application-prod.yml"))
        assertTrue(text.contains("\${SPRING_DATASOURCE_URL}"))
        assertTrue(text.contains("\${SPRING_DATASOURCE_USERNAME}"))
        assertTrue(text.contains("\${SPRING_DATASOURCE_PASSWORD}"))
        assertTrue(text.contains("\${JWT_SECRET}"))
        assertFalse(text.contains("local-only-development-secret"))
    }
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `.\gradlew.bat test --tests com.launcharchitecture.config.CorsAndProdConfigTest`

Expected: FAIL，CORS 头不存在或 `application-prod.yml` 不存在。

- [ ] **Step 3: 实现跨域、正式配置和启动说明**

在 `SecurityConfig` 增加导入 `org.springframework.web.cors.CorsConfiguration`、`CorsConfigurationSource`、`UrlBasedCorsConfigurationSource`，并增加 Bean：

```kotlin
@Bean
fun corsConfigurationSource(): CorsConfigurationSource {
    val config = CorsConfiguration()
    config.allowedOrigins = listOf("http://localhost:5173")
    config.allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
    config.allowedHeaders = listOf("Authorization", "Content-Type", "X-Request-Id")
    config.exposedHeaders = listOf("X-Request-Id")
    val source = UrlBasedCorsConfigurationSource()
    source.registerCorsConfiguration("/**", config)
    return source
}
```

把 `securityFilterChain` 里的第一行配置从 `.csrf { it.disable() }` 改成先启用跨域：

```kotlin
http
    .cors { }
    .csrf { it.disable() }
```

后面的 `sessionManagement`、`authorizeHttpRequests`、`oauth2ResourceServer` 保持 Task 5 的写法。

`application-prod.yml`：

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}
    driver-class-name: org.postgresql.Driver
app:
  jwt:
    secret: ${JWT_SECRET}
```

`server/README.md`：

```markdown
# Server

需要 Java 21。

本地启动：

```
.\gradlew.bat bootRun
```

健康检查：`http://localhost:8080/api/v1/health`

正式环境使用 `--spring.profiles.active=prod`，并提供 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`、`JWT_SECRET`。

测试：

```
.\gradlew.bat test
```
```

- [ ] **Step 4: 跑全部后端测试**

Run: `.\gradlew.bat test`

Expected: BUILD SUCCESSFUL。包含健康检查、错误体、契约文件、用户唯一邮箱、注册、登录、过期访问令牌、刷新轮换、退出、笔记归属和 100 条上限、CORS。

- [ ] **Step 5: Commit**

在仓库根目录：

```bash
git add server
git commit -m "feat: allow the local web origin and require prod secrets"
```
