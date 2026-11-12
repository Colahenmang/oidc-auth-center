# AI Coding 过程与复现提示词

本文根据实际协作过程整理。提示词是复现用的整理版，覆盖需求澄清、分阶段实现和验收。实际实施及验证结果在后文记录。

## 项目上下文

- 开发一个基于 OIDC 的员工认证中心，为接入的应用提供统一登录，支持配置登录回调地址。以 Authing 的托管登录流程作为交互参考。
- 项目包括认证中心、工单演示应用和管理员回调配置页。工单页负责发起登录和展示当前员工，管理员可以修改已登记的回调地址。
- 开发环境为 Windows、Java 21 和 Maven，使用 Spring Boot 4.0.9、Spring Security 和服务端模板。认证中心在 `9000` 运行，工单演示在 `8081` 运行。

## Authing 产品参考

我先在 Authing 控制台创建用户池和标准 Web 应用，配置登录回调 URL，再点「体验登录」。认证成功后，浏览器跳到配置的演示回调页，地址中带有授权码。这个操作让我明确了认证中心、接入应用和回调地址分别承担什么职责。[Authing 托管登录体验](https://docs.authing.cn/v2/guides/basics/authenticate-first-user/use-hosted-login-page.html)

| 在 Authing 看到的流程 | 在项目中怎么体现 |
| --- | --- |
| 应用先登记，再设置可回跳的 URL | 认证中心预置工单应用；管理员可以修改回调，授权请求必须匹配已登记的完整地址。 |
| 用户在认证中心页面输入凭证 | 工单页只提供登录入口，认证中心验证员工账号。 |
| 登录成功后浏览器带授权码跳回应用 | 工单后端接收并兑换授权码，验证身份后页面显示当前员工，刷新时使用工单自己的会话。 |

## 复现提示词

### 1. 明确需求和技术方案

> 我需要实现一个小型 OIDC 认证中心，为独立的工单演示应用提供登录服务。请查阅 Spring Security 官方文档和示例，给出认证中心与工单应用各自的职责、最小的项目结构，以及每个阶段的验收条件。实施顺序是先跑通登录和回调，再增加管理员回调配置。请说明参考了哪些示例以及各自适用的部分。

### 2. 实现并验收登录闭环

> 用 Spring Boot 和 Spring Security 实现两个可独立启动的模块。认证中心提供员工登录和 OIDC 授权码流程，工单应用提供一个登录演示页。使用预设的测试员工和回调地址，让浏览器从工单跳到认证中心，验证身份后带授权码回到工单。工单后端兑换并验证 ID Token，再从验证后的身份中读取姓名和员工 ID。请验证错误密码会回到登录页，正确登录会显示当前员工，刷新后仍能识别用户。

### 3. 增加回调地址配置

> 在认证中心增加应用配置页，管理员可以查看和修改工单应用的登录回调地址。用 H2 持久化应用登记配置，收到授权请求时精确匹配协议、主机、端口和路径。请验证普通员工访问配置页会被拒绝，管理员修改后旧地址被拒绝，工单端切到新地址后能正常登录，以及重启认证中心后配置仍在。

### 4. 对照浏览器请求和源码验收

> 给出按顺序操作的验收步骤，并标出浏览器 Network 里要看的请求。解释怎样从 `302 Location` 看出回调地址，怎样从 `Set-Cookie` 和刷新后的 `Cookie` 看出工单会话已经建立。指出回调匹配、凭证兑换、页面读取员工身份分别由哪段项目代码或 Spring Security 组件负责。把启动步骤、验收命令和实测结果写进项目文档。

### 5. 打磨演示页面和容器启动方式

> 按面试题的验收流程打磨认证中心、工单页和管理员回调配置页。让正常登录、配置保存、地址不匹配以及权限不足时的反馈清楚可见。提供 Docker Compose 启动方式，保留 Java 本地启动。核对浏览器使用的 issuer 与容器内部访问的地址，确保工单仍校验原来的签发方，应用回调配置可随数据卷保留。记录实际运行过的检查及环境限制。

## 参考项目及用法

- 用 Spring Security 7.0 的官方入门对照认证中心的 OIDC 开关、协议端点、安全链、客户端登记和签名密钥。[Spring 授权服务器入门](https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/authorization-server/getting-started.html)
- 对照 Spring 官方示例里的服务端与客户端，看两边如何对应应用 ID、issuer 和回调。示例与本项目的依赖版本不同，类名和配置项以 7.0 文档和实际依赖为准。[官方授权服务示例](https://github.com/spring-attic/spring-authorization-server/blob/1.5.x/samples/demo-authorizationserver/src/main/java/sample/config/AuthorizationServerConfig.java) · [官方配套客户端](https://github.com/spring-attic/spring-authorization-server/blob/1.5.x/samples/demo-client/src/main/resources/application.yml)
- 工单端用 Spring Security 自带的授权码登录，自己实现的页面从服务端已验证的 OIDC 用户信息读取姓名和员工 ID。[Spring OIDC 客户端说明](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/)
- 认证中心沿用 Spring 的客户端登记模型。管理员修改数据库里的回调地址后，授权端点执行精确匹配。Spring 的默认 loopback 校验有端口例外，本演示的 Web 客户端把端口也纳入精确匹配。[Spring 回调校验扩展示例](https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/authorization-server/protocol-endpoints.html#oauth2-authorization-endpoint)
- 使用多阶段镜像构建两个 Java 模块。Compose 用 OIDC 发现端点检查认证中心是否就绪，并让工单后端通过服务名访问认证中心。[Docker 多阶段构建](https://docs.docker.com/get-started/docker-concepts/building-images/multi-stage-builds/) · [Compose 启动顺序](https://docs.docker.com/compose/how-tos/startup-order/) · [Spring 客户端登记配置](https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/client/core.html#oauth2Client-client-registration)

## Vibe Coding 实施过程

| 阶段 | 我确定的范围与顺序 | Codex 协作实现 |
| --- | --- | --- |
| 跑通登录 | 让项目扮演认证中心。先用一个工单页面证明登录后能回到独立的业务应用。 | 建立两个可独立启动的模块，预设员工与回调。Spring Security 处理 OIDC，工单端显示验证后的身份。 |
| 配置回调 | 登录跑通后再实现管理员配置。把旧地址被拒绝、新地址能登录作为验收条件。 | 用 Spring 的 JDBC 客户端登记存储和本地 H2 保存回调地址，增加管理员编辑页和完整 URI 校验。 |
| 验收与交付 | 用浏览器 Network 把回跳和刷新后的登录状态对照到代码。以本地可运行作为主要验收方式。 | 补充联调脚本、错误提示和启动说明，并增加 Compose 启动配置。 |

## 实际验收结果

**我在浏览器里验证的步骤**

我用管理员账号保存回调地址，并在 Network 里看到配置表单提交了 `redirectUri`。我把回调恢复到默认的 `8081`，从工单页再次发起登录，看到认证中心返回 `302`，浏览器随后访问 `8081/login/oauth2/code/work-order?code=…`。工单回调响应里有 `Set-Cookie: DEMO_SESSION=…`，刷新工单首页的请求里则带有 `Cookie: DEMO_SESSION=…`。那次浏览器沿用了管理员身份，工单页显示 `admin`，与当前登录身份一致。通过这次观察，我把回调地址检查、身份凭证兑换和工单会话分别对应到了认证中心、Spring Security 客户端和工单首页代码。

**Codex 联调验证的步骤**

- Maven 构建通过。PowerShell 联调覆盖错误密码、员工「小朱」正确登录、会话刷新、普通员工访问管理页被拒、未登记端口和原回调被拒，以及恢复默认地址。
- Edge 联调验证管理员保存新地址、认证中心重启后配置保留、工单端切换到新回调后成功登录。页面升级后重新检查了桌面和 390 像素宽度的布局、400 与 403 提示、登录、退出和回调切换。
- Compose 配置校验通过。本地模拟了浏览器地址和后端通信地址分离，工单后端成功通过内部地址换取凭证、读取公钥与用户信息。镜像构建和容器启动待在有 Docker Engine 的环境实测。
