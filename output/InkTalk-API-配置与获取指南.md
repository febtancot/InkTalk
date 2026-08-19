# InkTalk API 配置与获取指南

- 适用版本：InkTalk 0.2.0
- 更新日期：2026 年 8 月 19 日
- 适用对象：需要配置语音识别、AI 文本处理或手写识别的使用者与维护人员

## 需要配置哪些服务

InkTalk 使用的外部能力分为三类：

| 能力 | 是否必需 | 服务 | 需要填写的内容 |
| --- | --- | --- | --- |
| 语音输入 | 必需 | 火山引擎豆包流式语音识别 | API Key，或旧版 App ID 与 Access Token；资源 ID |
| 总结、翻译、整理、自由语音指令 | 可选 | 任意兼容 OpenAI Chat Completions 的模型服务 | Base URL、API Key、模型 ID |
| 中文和英文手写识别 | 可选 | Google ML Kit Digital Ink Recognition | 不需要 API Key；首次使用时下载语言模型 |

语音识别和 AI 文本处理使用不同的服务与凭据。火山引擎豆包语音 API Key、火山方舟 API Key、阿里云百炼 API Key、OpenAI API Key 和 DeepSeek API Key 不能互相替代。

## 一、火山引擎豆包流式语音识别

### 1. 服务用途

InkTalk 使用火山引擎双向流式语音识别 WebSocket 接口完成实时语音转写。默认配置为豆包流式语音识别模型 2.0 小时版：

- WebSocket：`wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async`
- 默认资源 ID：`volc.seedasr.sauc.duration`
- 官方协议文档：[双向流式语音识别 WebSocket](https://docs.volcengine.com/docs/6561/2630027?lang=zh)

### 2. 开通服务

1. 登录[火山引擎豆包语音控制台](https://console.volcengine.com/speech/new/overview?projectName=default)。
2. 找到「语音识别大模型」或「豆包流式语音识别模型 2.0」。
3. 按实际计费方式开通小时版或并发版。
4. 记录开通的模型版本和计费方式。InkTalk 中选择的资源 ID 必须与控制台开通项一致。

控制台页面名称可能随火山引擎更新而变化。若控制台没有显示目标服务，应先确认账号实名认证、服务地域、项目和开通权限。

### 3. 获取新版 API Key

1. 打开[豆包语音 API Key 管理](https://console.volcengine.com/speech/new/setting/apikeys?projectName=default)。
2. 创建或复制当前项目可用的 API Key。
3. 在 InkTalk 设置页的「语音识别」区域填写「API Key」。
4. 使用新版 API Key 时，将旧版 App ID 和 Access Token 留空。

新版鉴权在 WebSocket 握手时使用 `X-Api-Key`。InkTalk 会自动生成请求 ID 和协议序号，不需要手工填写。

### 4. 使用旧版 App ID 与 Access Token

旧版控制台用户可以打开[旧版语音服务控制台](https://console.volcengine.com/speech/service/10035)查询 App ID 和 Access Token。

在 InkTalk 中：

- 「API Key」留空。
- 「App ID」填写旧版 App ID，对应请求头 `X-Api-App-Key`。
- 「Access Token」填写旧版 Access Token，对应请求头 `X-Api-Access-Key`。

不要同时依赖两套凭据。只要「API Key」非空，InkTalk 就优先使用新版 API Key。

### 5. 选择正确的资源 ID

| InkTalk 选项 | 资源 ID | 适用情况 |
| --- | --- | --- |
| 豆包流式识别 2.0 · 小时版 | `volc.seedasr.sauc.duration` | 默认推荐；按音频时长计费 |
| 豆包流式识别 2.0 · 并发版 | `volc.seedasr.sauc.concurrent` | 已购买并发资源的账号 |
| 豆包流式识别 1.0 · 小时版 | `volc.bigasr.sauc.duration` | 仍在使用 1.0 小时版的账号 |
| 豆包流式识别 1.0 · 并发版 | `volc.bigasr.sauc.concurrent` | 仍在使用 1.0 并发版的账号 |

资源 ID 与控制台开通项不一致时，可能出现 HTTP 401、403 或「resource not enabled」等错误。

### 6. 在 InkTalk 中验证

1. 打开 InkTalk 设置。
2. 保存语音凭据和资源 ID。
3. 点击「测试语音识别连接」。
4. InkTalk 会验证 WebSocket 握手、开始请求和服务端首包确认，不会访问麦克风。
5. 出现「ASR 协议验证成功」和 `logid` 后，再到真实输入框测试麦克风、实时文本和最终定稿。

连接测试成功不等于真实录音已经验收。麦克风权限、手机录音通道、网络切换、长时间连接和第三方编辑器仍需分别测试。

### 7. 排错链接

- [流式语音识别错误码](https://docs.volcengine.com/docs/6561/2611432?lang=zh)
- [QPS 与并发查询说明](https://docs.volcengine.com/docs/6561/1476626?lang=zh)
- [调用量查询说明](https://docs.volcengine.com/docs/6561/1476625?lang=zh)
- [热词配置](https://docs.volcengine.com/docs/6561/155739?lang=zh)
- [热词与上下文最佳实践](https://docs.volcengine.com/docs/6561/2604976?lang=zh)

## 二、OpenAI 兼容 AI 文本服务

### 1. InkTalk 的接口要求

AI 文本处理是可选功能，用于总结、翻译、整理和自由语音指令。InkTalk 固定使用以下兼容方式：

- 请求地址：`{Base URL}/chat/completions`
- 请求方法：`POST`
- 鉴权：`Authorization: Bearer {API Key}`
- 请求主体：`model` 与 `messages`
- 响应读取：`choices[0].message.content`

因此，提供方必须兼容 OpenAI Chat Completions 的请求和响应结构。填写 Base URL 时不要附加 `/chat/completions`，InkTalk 会自动追加该路径。

### 2. 设置字段说明

| 字段 | 填写要求 | 示例 |
| --- | --- | --- |
| Base URL | 填写到版本根路径，不包含 `/chat/completions` | `https://api.openai.com/v1` |
| API Key | 从模型服务商控制台创建的调用密钥 | 不要填写 ChatGPT、百炼或方舟的登录密码 |
| 模型 | 提供方支持 Chat Completions 的模型 ID | `gpt-4o-mini` |

保存后点击「测试 AI 连接」。InkTalk 会发送一段固定测试文本，这属于一次真实模型调用，可能产生费用。

### 3. OpenAI API

获取步骤：

1. 登录 [OpenAI API Platform](https://platform.openai.com/)。
2. 在 [API Keys](https://platform.openai.com/api-keys) 页面创建项目 API Key。
3. 根据账号状态配置 API 计费与用量限制。ChatGPT 订阅与 API 用量属于不同产品，API 可用额度以 API Platform 为准。
4. 在 InkTalk 中填写以下内容：

| InkTalk 字段 | 建议值 |
| --- | --- |
| Base URL | `https://api.openai.com/v1` |
| API Key | 在 OpenAI API Platform 创建的项目 API Key |
| 模型 | `gpt-4o-mini` |

`gpt-4o-mini` 当前支持 `v1/chat/completions`。模型能力与可用端点见 [OpenAI Docs：GPT-4o mini](https://developers.openai.com/api/docs/models/gpt-4o-mini)，鉴权方式见 [OpenAI API Authentication](https://platform.openai.com/docs/api-reference/authentication)。

OpenAI 官方不建议把长期 API Key 放入移动客户端。InkTalk 当前是本机 BYOK（自带密钥）工具，适合个人受控设备；面向团队或公开分发时，应使用自己的服务端代理保存密钥，并给移动端签发受限的短期凭据。

### 4. 阿里云百炼与千问

获取步骤：

1. 阅读[获取与配置百炼 API Key](https://help.aliyun.com/zh/model-studio/get-api-key/)。
2. 进入[阿里云百炼控制台](https://bailian.console.aliyun.com/?tab=model)，选择业务空间并创建 API Key。
3. 确认 API Key 所属地域、业务空间和计费方案。
4. 从[百炼 Base URL 总览](https://help.aliyun.com/zh/model-studio/base-url)选择与 API Key 匹配的地址。

中国大陆按量付费的简单配置：

| InkTalk 字段 | 建议值 |
| --- | --- |
| Base URL | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| API Key | 百炼中国大陆版 API Key |
| 模型 | `qwen-plus` |

生产环境可以使用业务空间专属地址：

`https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1`

将 `{WorkspaceId}` 替换为控制台显示的业务空间 ID。API Key、地域、业务空间和 Base URL 必须配套，否则通常返回 HTTP 401。

Token Plan 和 Coding Plan 的专属 Key 仅适用于对应的交互式 AI 工具，不应当作为 InkTalk 的通用模型服务 Key。InkTalk 应使用允许应用调用的按量付费或业务空间 API Key。

### 5. 火山方舟豆包大模型

获取步骤：

1. 登录[火山方舟控制台](https://console.volcengine.com/ark/region:ark+cn-beijing/overview)。
2. 在模型列表或体验中心选择支持 Chat API 的模型，并记录模型 ID。
3. 在[火山方舟 API Key 管理](https://console.volcengine.com/ark/region:ark+cn-beijing/apikey)创建 API Key。
4. 参考[火山方舟开始使用](https://docs.volcengine.com/docs/82379/1795150)和 [ChatCompletions API](https://api.volcengine.com/api-docs/view?action=ChatCompletions&serviceCode=ark&version=2024-01-01)确认模型 ID 与接口。

InkTalk 配置示例：

| InkTalk 字段 | 示例值 |
| --- | --- |
| Base URL | `https://ark.cn-beijing.volces.com/api/v3` |
| API Key | 火山方舟 API Key |
| 模型 | `doubao-seed-2-0-lite-260215`，或控制台当前显示的其他 Chat 模型 ID |

火山方舟 API Key 与豆包语音 API Key 属于不同产品。语音识别区域必须填写豆包语音凭据；AI 文本处理区域才填写火山方舟凭据。

### 6. DeepSeek API

获取步骤：

1. 登录 [DeepSeek Platform](https://platform.deepseek.com/)。
2. 在 [API Keys](https://platform.deepseek.com/api_keys) 页面创建密钥。
3. 根据平台要求充值或确认可用余额。
4. 参考 [DeepSeek API Quick Start](https://api-docs.deepseek.com/) 选择当前模型。

InkTalk 配置示例：

| InkTalk 字段 | 建议值 |
| --- | --- |
| Base URL | `https://api.deepseek.com` |
| API Key | DeepSeek Platform API Key |
| 模型 | `deepseek-v4-flash` 或 `deepseek-v4-pro` |

DeepSeek 当前官方文档使用 `deepseek-v4-flash` 和 `deepseek-v4-pro`。不要继续照抄旧文档中的 `deepseek-chat` 或 `deepseek-reasoner`，应以控制台和当前 Quick Start 为准。

### 7. 关闭思考模式开关

InkTalk 的「关闭思考模式」会同时发送多种兼容字段：

- `enable_thinking=false`
- `thinking={"type":"disabled"}`
- `reasoning_effort="minimal"`

这些字段分别面向不同提供方，并非所有服务都接受未知参数。初次配置时建议关闭此开关。只有确认提供方兼容后再开启；若返回 HTTP 400 或「unknown field」，应关闭开关并重新测试。

### 8. 常见 AI 错误

| 现象 | 常见原因 | 处理方式 |
| --- | --- | --- |
| HTTP 401 | API Key 错误；Key 与地域或 Base URL 不匹配 | 重新复制 Key，并核对地域、业务空间和计费方案 |
| HTTP 403 | 模型未授权；账号无权限；余额或服务状态异常 | 在提供方控制台开通模型并检查权限 |
| HTTP 404 | Base URL 多填或少填版本路径；模型 ID 不存在 | Base URL 不要包含 `/chat/completions`，重新复制模型 ID |
| HTTP 400 | 模型不支持 Chat Completions；关闭思考模式附加字段不兼容 | 更换 Chat 模型，或关闭「关闭思考模式」 |
| 解析响应失败 | 提供方响应不是 `choices[0].message.content` 结构 | 使用真正兼容 OpenAI Chat Completions 的接口 |
| 请求超时 | 网络不可达；模型响应超过 InkTalk 的 60 秒读取超时 | 检查网络，或改用响应更快的模型 |

## 三、Google ML Kit 手写识别

InkTalk 的中英文手写识别使用 Google ML Kit Digital Ink Recognition 19.0.0。该功能不需要申请 API Key，也不需要填写 Base URL。

首次选择某种手写语言时，InkTalk 会按需下载对应的语言模型。根据 [Google ML Kit Digital Ink Recognition 官方文档](https://developers.google.com/ml-kit/vision/digital-ink-recognition/android)，每种语言模型大约需要 20 MB 存储空间。

使用要求：

- 首次下载模型时需要网络连接。
- 设备需要能够访问 Google 提供的模型下载服务。
- 模型下载完成后，识别过程在设备上执行，不需要用户提供云端 API Key。
- 中文和英文模型分别下载；切换语言时可能触发新的下载。

若手写页面一直显示准备模型或下载失败，应检查网络、设备存储空间、Google 模型下载服务可达性和系统对后台下载的限制。

## 四、密钥保存与备份安全

InkTalk 将凭据保存到应用私有的 SharedPreferences，但「配置导出」文件会包含语音识别和 AI 服务密钥。

- 不要把导出的 JSON 文件发送到聊天群、公开仓库或工单附件。
- 更换手机后导入配置，应在确认成功后删除外部存储中的明文备份。
- 手机丢失、备份泄露或怀疑 Key 被复制时，应立即在对应服务控制台撤销并重建 Key。
- API Key 通常关联计费账户。建议在服务商控制台设置预算、额度和模型范围。移动网络 IP 经常变化，配置 IP 白名单前应确认不会阻断手机访问。
- 面向多人或公开发布时，不应继续在客户端保存长期有效的生产密钥，应改用服务端代理和短期凭据。

## 五、完整配置检查表

### 只使用语音输入

- 已开通豆包流式语音识别。
- 已获取豆包语音 API Key，或旧版 App ID 与 Access Token。
- InkTalk 资源 ID 与控制台开通项一致。
- 「测试语音识别连接」成功。
- 已授予录音权限，并在真实输入框完成录音测试。

### 使用 AI 文本处理与自由语音指令

- 已选择兼容 OpenAI Chat Completions 的提供方。
- Base URL 不包含 `/chat/completions`。
- API Key、地域、计费方案和 Base URL 相互匹配。
- 模型 ID 支持 Chat Completions。
- 「测试 AI 连接」成功。
- 已了解文本会发送到所选第三方模型服务，并接受对应服务的隐私与计费条款。

### 使用手写识别

- 不需要申请 API Key。
- 首次使用时保持网络可用。
- 为每个需要的语言模型预留约 20 MB 存储空间。
- 等待模型下载完成后再开始书写。

## 六、InkTalk 不使用的凭据

以下内容不应填写到 InkTalk 的 API Key 字段：

- ChatGPT 登录密码、ChatGPT 会话 Cookie 或 ChatGPT 订阅信息。
- 阿里云账号登录密码、火山引擎账号登录密码。
- Google 账号密码或 Google Cloud 服务账号密钥。
- Android 输入法授权码、短信验证码或手机解锁密码。

InkTalk 不会要求上传这些信息。设置页只需要对应服务商正式创建的 API Key、旧版语音凭据和模型配置。
