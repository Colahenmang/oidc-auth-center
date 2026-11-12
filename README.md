# 员工统一认证中心

一个小型 OpenID Connect（OIDC）认证中心。以 Authing 的应用登记和托管登录流程为参考，由独立的工单应用演示员工登录、回调和身份展示。管理员可以修改工单应用登记的登录回调地址。

| 模块 | 入口 | 功能 |
| --- | --- | --- |
| `auth-server` | `http://127.0.0.1:9000/` | 员工登录、OIDC 协议端点、应用回调配置 |
| `demo-client` | `http://127.0.0.1:8081/` | 发起登录、接收回调、显示已认证员工 |

## 本地启动

已实测 Java 本地启动和浏览器登录。使用 JDK 21、Maven 3.6.3 或更高版本，在仓库根目录运行以下命令。

```powershell
mvn -B -ntp package
java -jar auth-server/target/auth-server-0.1.0-SNAPSHOT.jar
```

认证中心启动后，在另一个终端从仓库根目录启动工单应用。

```powershell
java -jar demo-client/target/demo-client-0.1.0-SNAPSHOT.jar
```

启动后打开 [工单演示](http://127.0.0.1:8081/) 或 [应用配置](http://127.0.0.1:9000/admin/application)。

## 本地演示账号

浏览器入口绑定本机。项目预置一个工单应用和以下两个演示账号。

| 用途 | 账号 | 密码 |
| --- | --- | --- |
| 登录工单演示 | `employee` | `Demo-Employee-2026!` |
| 管理应用回调地址 | `admin` | `Demo-Admin-2026!` |

使用 Java 启动时，应用配置保存在认证中心的 `data/identity-center.mv.db`。员工和管理员账号启动时初始化在内存，认证中心在内存里保存密码的 BCrypt 哈希，签名密钥也在启动时生成。使用 Java 启动时可用 `DEMO_EMPLOYEE_PASSWORD`、`DEMO_ADMIN_PASSWORD` 覆盖演示密码。`DEMO_CLIENT_SECRET` 如需覆盖，两端须使用同一值。

## 演示与验收

| 验收项 | 操作与预期结果 |
| --- | --- |
| 为外部应用提供 OIDC 登录 | 从 [工单页](http://127.0.0.1:8081/) 点「登录」，浏览器转到认证中心。先输一次错误密码，确认表单显示错误。用员工账号登录后返回工单页，显示「小朱」和员工 ID `employee`。刷新后仍能识别员工。 |
| 管理员配置回调地址 | 用独立的浏览器会话进入[应用配置](http://127.0.0.1:9000/admin/application)，以管理员身份登录。把默认回调 `http://127.0.0.1:8081/login/oauth2/code/work-order` 改成 `http://127.0.0.1:8081/login/oauth2/code/work-order-alternate` 并保存。重新打开配置页，确认看到新地址。 |
| 只接受已登记的回调地址 | 从未登录的工单页重新发起登录。工单端仍请求原地址时，认证中心返回 `400`，页面提示核对回调地址。让工单端使用同一个新地址后，再次登录应成功返回工单。 |

工单页的「退出工单」只退出工单会话。需要切换员工和管理员时，可以用独立的浏览器会话，或先在认证中心首页退出当前账号。

先在启动工单应用的终端设置新回调地址。

```powershell
$env:DEMO_REDIRECT_URI = 'http://127.0.0.1:8081/login/oauth2/code/work-order-alternate'
```

使用 Compose 时，重新创建工单容器。

```sh
docker compose up -d --force-recreate demo-client
```

使用 Java 时，停止当前工单应用，在同一终端重新运行工单 JAR。

演示完把管理页的回调改回默认值，在工单终端运行 `Remove-Item Env:DEMO_REDIRECT_URI`，再按所用的方式重启工单应用。

### 自动联调

服务启动后，可在 PowerShell 7 运行以下命令。脚本使用默认演示账号和默认回调地址。第一条覆盖错误密码、正确登录、会话刷新和回调端口不匹配。第二条还会验证员工访问管理页被拒、管理员修改回调后旧地址被拒，并在结束时恢复原配置。新地址成功登录可按上面的浏览器步骤验收。

```powershell
pwsh -File ./scripts/Test-Login.ps1
pwsh -File ./scripts/Test-Login.ps1 -IncludeCallbackConfig
```

## 登录时每一方负责什么

1. 工单应用发起 OIDC 请求，携带它自己的应用 ID 和回调地址。认证中心以数据库里的登记值精确校验地址。
2. 员工通过认证后，浏览器带着短期、一次性的授权码回到工单后端。
3. 工单后端使用 PKCE 校验值向认证中心兑换 `ID Token`。Spring Security 处理授权码兑换和身份凭证验证。
4. 工单后端从验证后的身份中读取姓名和员工 ID，建立自己的登录会话。认证中心和工单端各自使用独立的会话 Cookie。

### 代码入口

- [认证中心安全配置](auth-server/src/main/java/com/example/identity/auth/SecurityConfiguration.java)启用 OIDC，在授权请求里精确比对已登记的回调地址。
- [应用配置控制器](auth-server/src/main/java/com/example/identity/auth/ApplicationSettingsController.java)将管理员保存的回调地址写入 H2。
- [工单登录配置](demo-client/src/main/java/com/example/identity/demo/DemoSecurity.java)用 Spring Security 处理授权码兑换和会话。[工单首页控制器](demo-client/src/main/java/com/example/identity/demo/WorkOrderController.java)从已认证用户读取姓名和 ID。

## Docker Compose 启动

Compose 配置校验通过，容器构建和启动待在有 Docker Engine 的环境实测。两种启动方式使用相同端口，切换前先停止占用 `9000` 和 `8081` 的服务。

```sh
docker compose up --build -d
docker compose ps
```

Compose 在认证中心的 OIDC 发现端点可用后启动工单应用。浏览器访问 `127.0.0.1:9000`，工单后端通过 `auth-server:9000` 访问认证中心，验证 ID Token 时使用浏览器访问的地址作为签发方。H2 应用配置保存在 `auth-data` 命名卷。

```sh
docker compose down
```

[AI Coding 过程、复现提示词与参考来源](docs/AI-CODING.md)
