# InkTalk

纯语音 Android 输入法：按住即说、实时上屏，基于火山引擎「豆包流式语音识别大模型 2.0」，并可选接入任意 OpenAI 兼容大模型做「自由语音指令 / 总结 / 翻译 / 整理」。

当前版本：0.2.0。详见 [InkTalk 0.2.0 产品发布说明](output/InkTalk-0.2.0-产品发布说明.md)和 [API 配置与获取指南](output/InkTalk-API-配置与获取指南.md)。

## 功能

- 🎤 **中英混合与英文优先**：设置为“实时中英混合”时，键盘显示“中英混合 / 123”，中英文共用 `bigmodel_async`；设置为“英文优先定稿”时，显示“中文 / 123 / English”，English 使用 `bigmodel_nostream + en-US`。数字口述会规范为阿拉伯数字。
- ⚡ **低延迟**：使用官方推荐的双向流式优化版端点（bigmodel_async），200 ms 音频分包，结果变化才下发。
- ☀️ **识别期间常亮**：从连接开始到最终结果返回期间阻止系统因无操作自动息屏；会话结束后恢复系统策略。
- 🧠 **AI 文本处理**：优先对编辑框中当前选中的文本执行总结 / 中英互译 / 整理；没有选中文本时处理本次语音内容。接入任意 OpenAI 兼容 API，可选择追加结果或直接替换原文。
- ✦ **自由语音指令**：麦克风左侧使用无文字胶囊，在“普通麦克风 / 带星标的指令麦克风”间滑动切换。指令模式中的下一段语音只作为修改要求，不会写入文本框。支持选区修改、空文本生成和全文修改；选区模式仍把完整文本框发送给模型作为上下文，但结果只替换选区。模型结果先在面板中预览，确认后才写入。
- 🔥 **热词**：内置 AI、科技、互联网企业与 IELTS 中英文词表；可在独立热词页逐行编辑、自动去重或恢复默认内容。
- ⌘ **快捷键面板**：点工具栏 ⌘ 以横向滑动动画切换编辑快捷键：ESC / Tab / 全选 / 复制 / 粘贴 / 撤销（Ctrl+Z）/ 方向键；按键采用 5×2 等宽网格。
- 🔄 **输入法切换**：右上键盘按钮切换到下一个输入法，长按打开系统输入法选择器。
- 📳 **系统触觉反馈**：麦克风、模式切换、AI 工具、编辑键和快捷键使用 Android 原生触觉反馈；长按连续删除时每次删除都会触发轻震。反馈自动遵循系统“触摸反馈”开关，不额外申请震动权限。
- 🎛️ **可控参数**：智能标点、逆文本规范化（ITN）、语义顺滑（DDC）均可在设置中开关。
- 🔒 **凭据不内置**：App ID / Access Token / API Key 默认存放在本机 SharedPreferences。配置导出文件会包含这些凭据，需按密钥文件妥善保管。
- 💾 **配置迁移**：通过独立的“导入与导出”页面备份语音识别、热词、AI 和输入模式配置；每日输入记录不会写入配置文件。

## 接入原理（火山引擎 SAUC 协议）

文档：[双向流式语音识别 WebSocket](https://docs.volcengine.com/docs/6561/2630027?lang=zh)

- 端点：`wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async`
- 资源 ID：`volc.seedasr.sauc.duration`（豆包流式语音识别大模型 2.0 · 小时版）
- 握手 Header：`X-Api-Resource-Id`、随机 `X-Api-Request-Id` 和 `X-Api-Sequence: -1`。新版控制台使用 `X-Api-Key`；旧版控制台使用 `X-Api-App-Key` + `X-Api-Access-Key`。响应头 `X-Tt-Logid` 用于排错。
- 二进制协议：4 字节 header + 请求序号 + payload size + payload，整数使用大端，payload 使用 gzip 压缩。首包序号为 1，后续音频包递增。
- 音频：16 kHz / 单声道 / PCM s16le，200 ms 一包；结束时发送带负序号的尾包。
- 识别参数：`enable_nonstream=true`（async 端点专属二遍识别，`definite` 仅由二遍输出）、`show_utterances=true`、`result_type=single`、`end_window_size=800`。
- 热词：按用户顺序选取，并使用保守的 80-token 客户端预算，避免超过服务端 100-token 上限后被静默截断。

## 准备工作

1. 开通服务：火山引擎控制台 → 语音技术 → 开通「豆包流式语音识别大模型 2.0」（按音频时长计费）。新版控制台获取 **API Key** 即可；旧版控制台则需 **APP ID** + **Access Token**。
2. （可选）准备任意 OpenAI 兼容 API 的 Base URL / API Key / 模型名，用于 AI 功能。

## 构建

需要 JDK 17+ 与 Android SDK（本工程使用 compileSdk 37 / minSdk 26）：

```bash
# 首次构建会自动下载 Gradle 9.7
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 安装与使用

1. 安装 APK，打开 InkTalk 设置页。
2. 按「快速开始」三步走：启用输入法 → 切换到 InkTalk → 授予录音权限。
3. 填入 API Key（或旧版的 App ID + Access Token），点「测试 ASR 连接」验证握手和首包协议（成功后显示 logid）。
4. 如需调整内置词表，在「词表与数据 → 编辑热词」中逐行编辑并保存。
5. 在任意输入框唤出 InkTalk，先选中文、123 或 English，再点大麦克风开始说话；再次点击结束。
6. 使用自由指令时，先按需选中文字，再点“指令”并说出修改要求。无选区的非空文本框会进入全文修改，空文本框会在光标处生成；检查预览后点“替换”。

## 已知限制

- AI 结果默认追加在选中文本或本次语音原文之后。设置中开启“直接替换原文”后，InkTalk 会优先替换当前选中文本；没有选中文本时，才替换本次语音原文。若处理期间选区、光标或原文发生变化，会取消写入以避免误删。
- 指令模式依赖宿主 App 通过 Android `InputConnection.getExtractedText()` 提供可靠的完整文本和选区。若宿主只提供局部上下文，InkTalk 会拒绝执行，不会把残缺文本当作全文。
- 指令模式单次完整上下文上限为 6,000 个 UTF-16 字符，且在密码、PIN 等敏感输入框中禁用。处理期间只要全文、选区或光标发生变化，旧结果就不能写入。
- AI 响应慢可能与模型默认开启「思考模式」有关。「关闭思考模式」会同时发送 `enable_thinking=false`、`thinking={type:disabled}` 和 `reasoning_effort=minimal` 三种兼容参数；部分提供方会因未知参数返回 HTTP 400，此时应关闭该开关，并按照 [API 配置与获取指南](output/InkTalk-API-配置与获取指南.md)选择当前支持的模型。
- InkTalk 默认不再设置 120 秒本地上限。当前火山引擎双向流式接口文档未声明固定的单次会话最大时长；实际连接仍可能因服务端策略、网络或系统资源状态而结束。
- 火山引擎的 `bigmodel_async` 不能固定 `language` 参数，因此“实时中英混合”不严格过滤中文；“英文优先定稿”虽发送 `en-US`，也仍可能返回中文内容。
- 识别参数（语言、采样率 16k、endpointing 800ms）暂为固定推荐值，未全部开放到设置页。
- 快捷键面板的「撤销」发送的是 Ctrl+Z 按键事件，是否生效取决于目标 App 自身是否实现撤销。

## 目录结构

```
app/src/main/java/com/inktalk/ime/
├── InkTalkIME.kt          # InputMethodService：面板 UI、上屏逻辑、AI 触发
├── asr/
│   ├── SaucProtocol.kt    # SAUC 二进制协议编解码（gzip / 大端 / 帧类型）
│   ├── VolcAsrClient.kt   # OkHttp WebSocket 客户端（鉴权握手、收发帧）
│   ├── AudioCapturer.kt   # AudioRecord 16k PCM，200ms 分片 + 音量回调
│   └── AsrSession.kt      # 会话状态机：增量/定稿结果合并、超时兜底
├── ai/
│   ├── AiProcessor.kt                 # OpenAI 兼容 chat/completions 调用
│   ├── InstructionDocument.kt         # 三种目标范围、全文上下文与 prompt 契约
│   └── InstructionContextResolver.kt  # ExtractedText 读取与敏感字段保护
├── settings/              # 设置页 + SharedPreferences 封装
└── ui/WaveformView.kt     # 音量波形
```
