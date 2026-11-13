# 员工统一认证中心

一个小型 OpenID Connect（OIDC）认证中心。以 Authing 的应用登记和托管登录流程为参考，工单和人事演示各自接入同一个认证中心。管理员可以登记新应用、维护各应用的登录回调地址，并在员工管理页创建、搜索员工和管理人事访问权限。

工单和人事是预置接入应用。财务演示由管理员在界面登记后启动，用于验证新应用可以使用独立的客户端凭据接入。各业务页面用于验收统一登录。员工管理页可以为指定员工开通或取消人事访问。

| 模块 | 入口 | 功能 |
| --- | --- | --- |
| `auth-server` | `http://127.0.0.1:9000/` | 员工登录、OIDC 协议端点、应用回调配置 |
| `demo-client` 工单实例 | `http://127.0.0.1:8081/` | 工单登录演示 |
| `demo-client` 人事实例 | `http://127.0.0.1:8082/` | 人事登录和单点登录演示 |
| `demo-client` 财务实例 | `http://127.0.0.1:8083/` | 登记新应用后的接入演示 |

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

在第三个终端用同一个 JAR 启动人事演示。

```powershell
java -jar demo-client/target/demo-client-0.1.0-SNAPSHOT.jar --spring.profiles.active=hr
```

启动后打开 [工单演示](http://127.0.0.1:8081/)、[人事演示](http://127.0.0.1:8082/)、[应用配置](http://127.0.0.1:9000/admin/application) 或 [员工管理](http://127.0.0.1:9000/admin/employees)。

### 登记并启动财务演示

1. 以管理员身份进入[应用配置](http://127.0.0.1:9000/admin/application)，点「新增应用」。应用名称填「财务演示」，回调地址填 `http://127.0.0.1:8083/login/oauth2/code/finance`。
2. 提交后记下生成的应用 ID 和应用密钥。明文密钥仅在创建成功时显示一次，由财务应用后端使用。在项目根目录打开 PowerShell 7，运行下列命令，在提示时粘贴对应的值。

```powershell
$env:DEMO_FINANCE_CLIENT_ID = Read-Host 'Client ID'
$env:DEMO_FINANCE_CLIENT_SECRET = Read-Host 'Client Secret' -MaskInput
java -jar demo-client/target/demo-client-0.1.0-SNAPSHOT.jar --spring.profiles.active=finance
```

打开[财务演示](http://127.0.0.1:8083/)。两个凭据为必填项。使用备用回调地址验收时，在管理员页保存新地址，再停止财务实例并在同一终端设置下列变量后重新启动。

```powershell
$env:DEMO_FINANCE_REDIRECT_URI = 'http://127.0.0.1:8083/login/oauth2/code/finance-alternate'
java -jar demo-client/target/demo-client-0.1.0-SNAPSHOT.jar --spring.profiles.active=finance
```

恢复默认回调时，在管理员页保存原地址，再运行 `Remove-Item Env:DEMO_FINANCE_REDIRECT_URI` 并重启财务实例。

## 本地演示账号

浏览器入口绑定本机。项目预置工单和人事两个应用，以及以下两个演示账号。

| 用途 | 账号 | 密码 |
| --- | --- | --- |
| 登录工单、人事和财务演示 | `employee` | `Demo-Employee-2026!` |
| 登记应用、管理回调地址、员工和人事访问权限 | `admin` | `Demo-Admin-2026!` |

使用 Java 启动时，应用配置、员工账号及其 BCrypt 密码哈希、人事权限保存在认证中心的 `data/identity-center.mv.db`。管理员和签名密钥在启动时初始化。可用 `DEMO_EMPLOYEE_PASSWORD`、`DEMO_ADMIN_PASSWORD` 覆盖演示密码。覆盖 `DEMO_CLIENT_SECRET` 或 `DEMO_HR_CLIENT_SECRET` 时，认证中心和对应的演示应用须使用同一值。

## 演示与验收

| 验收项 | 操作与预期结果 |
| --- | --- |
| 为外部应用提供 OIDC 登录 | 从 [工单页](http://127.0.0.1:8081/) 点「登录」，浏览器转到认证中心。先输一次错误密码，确认表单显示错误。用员工账号登录后返回工单页，显示「小朱」和员工 ID `employee`。刷新后仍能识别员工。 |
| 两个应用分别保存回调 | 在应用配置页记录工单回调，将人事回调末尾的 `/hr` 改为 `/hr-alternate` 并保存。刷新后人事显示新值，工单保留原值。再将人事改回 `/hr` 并保存。 |
| 单点登录 | 在新的无痕窗口用 `employee` 登录工单，看到「小朱」。在同一个窗口打开 [人事页](http://127.0.0.1:8082/)，确认初始页面提示登录。点「登录」后，认证中心沿用已有会话，浏览器回到人事页显示「小朱」，期间只在工单首次登录时输入密码。 |
| 新应用接入 | 按上节登记并启动财务应用。刷新应用配置，确认财务仍在列表且明文密钥已隐藏，工单和人事配置保持原值。在员工会话中进入[财务页](http://127.0.0.1:8083/)，发起登录后应回到财务并显示「小朱」。认证中心已有会话时可跳过密码。 |
| 新应用回调校验 | 把财务登记的回调改为 `http://127.0.0.1:8083/login/oauth2/code/finance-alternate`，退出财务后重新登录。仍使用原地址的财务请求应收到 `400`。用相同的新回调地址重启财务实例，再次登录应成功。验收后可将应用配置和启动环境变量一起恢复。 |
| 创建与搜索员工 | 在员工管理页创建新员工，确认总数增加，可按姓名或账号搜索。用新账号登录工单，确认显示创建时填写的姓名。 |
| 人事访问权限 | 在员工管理页找到要操作的员工，取消人事权限。在该员工的会话里退出人事后重新登录，页面应提示无权限。重新登录工单仍能成功。管理员重新开通后，从人事页再次发起登录，应恢复访问。 |
| 管理员配置回调地址 | 用独立的浏览器会话进入[应用配置](http://127.0.0.1:9000/admin/application)，以管理员身份登录。把默认回调 `http://127.0.0.1:8081/login/oauth2/code/work-order` 改成 `http://127.0.0.1:8081/login/oauth2/code/work-order-alternate` 并保存。重新打开配置页，确认看到新地址。 |
| 只接受已登记的回调地址 | 从未登录的工单页重新发起登录。工单端仍请求原地址时，认证中心返回 `400`，页面提示核对回调地址。让工单端使用同一个新地址后，再次登录应成功返回工单。 |

业务应用的退出按钮各自结束本应用的会话。需要切换员工和管理员时，可以用独立的浏览器会话，或先在认证中心首页退出当前账号。小朱初始开通人事权限，新员工初始可以登录工单，人事权限由管理员开通。权限变更在下一次发起人事登录时生效。

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

服务启动后，可在 PowerShell 7 运行以下命令。脚本使用默认演示账号和默认回调地址。第一条覆盖错误密码、正确登录、会话刷新和回调端口不匹配。第二条还会验证员工访问管理页被拒、管理员修改回调后旧地址被拒，并在结束时恢复原配置。第三条要求启动人事实例，会验证两个应用的会话独立，以及工单登录后能免输密码进入人事。工单已经切到备用回调时，单点登录检查加 `-UseAlternateWorkOrderCallback`。

```powershell
pwsh -File ./scripts/Test-Login.ps1
pwsh -File ./scripts/Test-Login.ps1 -IncludeCallbackConfig
pwsh -File ./scripts/Test-Login.ps1 -IncludeSso
pwsh -File ./scripts/Test-Login.ps1 -IncludeHrAccess
```

`-IncludeHrAccess` 会验证管理员可以开通和取消人事权限，普通员工无法修改权限，取消后仍可登录工单，并在检查结束后恢复原权限。工单使用备用回调时也可加 `-UseAlternateWorkOrderCallback`。

`-IncludeEmployeeManagement` 会验证创建、搜索、登录和逐人授权，并保留一个随机账号用于联调。请在临时数据库上运行这项检查。

## 登录时每一方负责什么

1. 工单应用发起 OIDC 请求，携带它自己的应用 ID 和回调地址。认证中心以数据库里的登记值精确校验地址。
2. 员工通过认证后，浏览器带着短期、一次性的授权码回到工单后端。
3. 工单后端使用 PKCE 校验值向认证中心兑换 `ID Token`。Spring Security 处理授权码兑换和身份凭证验证。
4. 工单后端从验证后的身份中读取姓名和员工 ID，建立自己的登录会话。人事或财务发起登录时，认证中心可以复用自己的会话。每个业务应用都有独立的登录会话和会话 Cookie。

### 代码入口

- [认证中心安全配置](auth-server/src/main/java/com/example/identity/auth/SecurityConfiguration.java)启用 OIDC，在授权请求里精确比对已登记的回调地址。
- [应用配置控制器](auth-server/src/main/java/com/example/identity/auth/ApplicationSettingsController.java)处理管理员登记新应用和修改回调地址，应用登记信息存放在 H2。
- [工单登录配置](demo-client/src/main/java/com/example/identity/demo/DemoSecurity.java)用 Spring Security 处理授权码兑换和会话。[工单首页控制器](demo-client/src/main/java/com/example/identity/demo/WorkOrderController.java)从已认证用户读取姓名和 ID。

## Docker Compose 启动

Compose 同时启动认证中心、工单和人事。财务演示按上面的 Java 步骤登记后单独启动。容器构建和启动待在有 Docker Engine 的环境实测。两种启动方式使用相同端口，切换前先停止占用 `9000`、`8081` 和 `8082` 的服务。

```sh
docker compose up --build -d
docker compose ps
```

Compose 在认证中心的 OIDC 发现端点可用后启动两个演示应用。浏览器访问 `127.0.0.1:9000`，演示应用后端通过 `auth-server:9000` 访问认证中心，验证 ID Token 时使用浏览器访问的地址作为签发方。H2 应用配置保存在 `auth-data` 命名卷。

```sh
docker compose down
```

[AI Coding 过程、复现提示词与参考来源](docs/AI-CODING.md)
