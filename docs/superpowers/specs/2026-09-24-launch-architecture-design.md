# LaunchArchitecture 第一版设计

日期：2026-09-24

## 目标

做一个一个人可以长期维护的启动骨架。三端只共享 HTTP API。第一版把一条业务竖切做通：注册并登录、看到自己的笔记、新建一条笔记。以后的具体软件在这条路径上加模块，而不是另起结构。

完成标准：用接口工具走完后端竖切；浏览器和 Android 用同一账号看到同一份笔记；仓库里有一份「如何新增一个业务模块」的说明。

## 已定决策

- 共享物只有 `contracts/openapi.yaml`。Web 和 Android 按契约手写调用，第一版不生成客户端代码。
- 后端：Spring Boot 3，Kotlin。包名 `com.launcharchitecture`。分层为 controller → service → repository。
- Web：Vue 3，Vite，TypeScript。
- Android：Kotlin，Jetpack Compose，`minSdk` 26，单模块 `:app`。
- 身份：邮箱加密码。不接手机号、第三方登录和角色权限。
- 示例资源：笔记。它用来演示模块怎么加，不代表未来产品的领域模型。
- 单仓，目录为 `contracts/`、`server/`、`web/`、`android/`、`docs/`。

## 第一版不做

iOS、微服务、消息队列、多租户、支付、插件系统、代码生成、复杂权限、游标分页。笔记列表一次最多返回 100 条，按 `updatedAt` 降序。

## 接口

前缀 `/api/v1`。JSON 字段使用 camelCase。时间用 UTC 的 ISO-8601。标识用 UUID。

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| POST | `/api/v1/auth/register` | 注册并直接登录 |
| POST | `/api/v1/auth/login` | 登录 |
| POST | `/api/v1/auth/refresh` | 轮换刷新令牌 |
| POST | `/api/v1/auth/logout` | 作废当前刷新令牌 |
| GET | `/api/v1/me` | 当前用户 |
| GET | `/api/v1/notes` | 当前用户的笔记，最多 100 条 |
| POST | `/api/v1/notes` | 新建笔记 |
| GET | `/api/v1/notes/{id}` | 读取自己的笔记 |
| PUT | `/api/v1/notes/{id}` | 修改自己的笔记 |
| DELETE | `/api/v1/notes/{id}` | 删除自己的笔记 |
| GET | `/api/v1/health` | 健康检查，无需登录 |

需要登录的接口使用 `Authorization: Bearer <accessToken>`。健康检查、注册、登录、刷新、退出不使用访问令牌。退出只凭刷新令牌作废会话，这样访问令牌过期后仍能退出。

### 请求与响应

注册：`email`、`password`、`displayName`。登录：`email`、`password`。刷新和退出：`refreshToken`。

注册、登录、刷新成功时返回：

```json
{
  "accessToken": "string",
  "refreshToken": "string",
  "expiresIn": 900
}
```

`expiresIn` 是访问令牌的秒数，固定 900。当前用户：

```json
{
  "id": "uuid",
  "email": "string",
  "displayName": "string"
}
```

笔记：

```json
{
  "id": "uuid",
  "title": "string",
  "body": "string",
  "createdAt": "2026-09-24T00:00:00Z",
  "updatedAt": "2026-09-24T00:00:00Z"
}
```

新建和修改笔记的正文为 `title`、`body`。未传 `body` 时按空字符串保存。修改会同时替换标题和正文。

成功时：注册、登录、刷新返回 200 和令牌；退出返回 204 且无正文；新建笔记返回 201 和笔记；笔记列表返回 200 和笔记数组；读取、修改笔记返回 200 和笔记；删除笔记返回 204。列表超过 100 条时只返回最近更新的 100 条，不返回总数。

健康检查返回 200：`{ "status": "up" }`。

访问令牌的 `sub` 为用户 id。后端监听 8080。Android 应用 id 为 `com.launcharchitecture`。跨域允许源 `http://localhost:5173`，并允许头 `Authorization`、`Content-Type`、`X-Request-Id`。

### 校验

- 邮箱：合法邮箱，保存前转成小写，全库唯一。
- 密码：8 到 72 个字符。上限配合 BCrypt。
- 显示名：1 到 40 个字符。
- 笔记标题：1 到 80 个字符。
- 笔记正文：0 到 4000 个字符。

## 账号

访问令牌是 JWT，有效期 15 分钟，签名密钥来自配置。刷新令牌是随机串，有效期 14 天，服务端只存哈希。刷新时签发新的访问令牌和新的刷新令牌，并作废提交上来的那枚刷新令牌。退出作废提交上来的刷新令牌。密码只存 BCrypt 哈希。

访问不属于当前用户的笔记，以及笔记不存在，都返回 404 和 `NOTE_NOT_FOUND`。

## 错误

每个响应都带 `X-Request-Id`。客户端传入则沿用，否则服务端生成 UUID。失败正文：

```json
{
  "code": "VALIDATION_ERROR",
  "message": "string",
  "requestId": "uuid"
}
```

| 情况 | HTTP | code |
| --- | --- | --- |
| 字段校验失败 | 400 | `VALIDATION_ERROR` |
| 缺少、过期或无效的访问令牌 | 401 | `UNAUTHENTICATED` |
| 刷新令牌无效、过期或已作废 | 401 | `INVALID_REFRESH_TOKEN` |
| 笔记不存在，或不属于当前用户 | 404 | `NOTE_NOT_FOUND` |
| 邮箱已被注册 | 409 | `EMAIL_ALREADY_USED` |

`message` 给人看，客户端以 `code` 分支。

## 数据

表由 Flyway 管理。

- `users`：`id`、`email`、`password_hash`、`display_name`、`created_at`
- `refresh_tokens`：`id`、`user_id`、`token_hash`、`expires_at`、`revoked_at`
- `notes`：`id`、`user_id`、`title`、`body`、`created_at`、`updated_at`

本地配置使用 H2 文件库。`prod` 配置使用 PostgreSQL，连接信息来自环境变量 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`。JWT 签名密钥来自 `JWT_SECRET`；本地配置带一个仅用于开发的默认值。

Web 开发源站为 `http://localhost:5173`，后端允许该源的跨域请求。

## 客户端

Web 页面：注册、登录、笔记列表、笔记编辑。访问令牌和刷新令牌放在 `localStorage`。访问令牌过期时用刷新令牌换新，失败则回到登录页。

Android 页面与 Web 相同。令牌放在 AndroidX Security 的加密存储中。过期与刷新行为和 Web 一致。

## 测试

后端集成测试使用 H2，至少覆盖：注册后能访问当前用户、邮箱重复返回 409、登录、新建并列出笔记、修改并删除笔记、不能读取另一个用户的笔记、无效刷新令牌返回 401。

Web 和 Android 第一版不设自动化端到端测试。`docs/acceptance.md` 记录同一份手工清单：注册、登录、新建笔记、刷新页面或重启应用后数据仍在、退出后不能继续访问。

## 交付顺序

1. 写 OpenAPI、统一错误体和请求 ID。
2. 完成后端竖切和集成测试。
3. 完成 Web，接上同一条链路。
4. 完成 Android，用同一账号看到同一份数据。
5. 写 `docs/adding-a-module.md`：新增模块时要改契约、Flyway、后端包、Web 页面、Android 页面，并补一条「只能访问自己的数据」的集成测试。说明以笔记模块为样例。

## 新增模块的边界

一个模块拥有自己的契约路径、数据表、后端包和两端页面。模块通过当前用户标识限定数据归属。模块之间不互相调用对方的表。笔记模块是这条约定的样例。
