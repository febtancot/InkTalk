package com.inktalk.ime.asr

import java.util.Locale

/**
 * InkTalk 内置热词及用户输入的标准化规则。
 *
 * 编辑页允许使用换行或中英文逗号分隔；发送给 ASR 前统一解析并去重。
 */
object HotwordCatalog {
    private val defaultGroups = listOf(
        // InkTalk 与个人项目
        """
        inktalk
        SpeakUp
        ThinkInk
        ForNow
        Nishuo
        KiMind
        DayDrop
        YayaDraw
        LiveBy
        """,
        // AI 企业、品牌与模型
        """
        人工智能
        生成式人工智能
        大语言模型
        多模态大模型
        智能体
        推理模型
        阿里云
        阿里云百炼
        通义千问
        火山引擎
        火山方舟
        豆包
        深度求索
        月之暗面
        智谱AI
        百川智能
        零一万物
        阶跃星辰
        科大讯飞
        讯飞星火
        百度文心
        腾讯混元
        腾讯元宝
        可灵AI
        海螺AI
        AI
        AGI
        OpenAI
        ChatGPT
        GPT-5
        Codex
        Sora
        DALL-E
        Anthropic
        Claude
        Claude Code
        Google DeepMind
        Gemini
        NotebookLM
        Microsoft Copilot
        GitHub Copilot
        Meta AI
        Llama
        xAI
        Grok
        DeepSeek
        Qwen
        DashScope
        Kimi
        Moonshot AI
        MiniMax
        ChatGLM
        Mistral AI
        Perplexity
        Midjourney
        Runway
        Hugging Face
        Cohere
        Stability AI
        Workers AI
        """,
        // AI 与科技术语
        """
        多模态模型
        视觉语言模型
        语音识别
        语音合成
        检索增强生成
        提示词工程
        上下文窗口
        函数调用
        工具调用
        模型推理
        模型微调
        向量数据库
        实时语音
        端侧模型
        云端推理
        人工智能体
        工作流
        知识库
        LLM
        VLM
        ASR
        TTS
        RAG
        Agent
        AI Agent
        MCP
        API
        SDK
        WebSocket
        Realtime API
        Function Calling
        Tool Calling
        Prompt Engineering
        Context Window
        Fine-tuning
        Embeddings
        Vector Database
        Transformer
        Tokens
        Inference
        Multimodal
        Speech-to-Text
        Text-to-Speech
        """,
        // 互联网企业与常用产品
        """
        字节跳动
        抖音
        腾讯
        微信
        阿里巴巴
        淘宝
        天猫
        蚂蚁集团
        支付宝
        百度
        京东
        美团
        拼多多
        小红书
        哔哩哔哩
        快手
        网易
        小米
        华为
        滴滴出行
        携程集团
        新浪
        微博
        知乎
        ByteDance
        TikTok
        Tencent
        WeChat
        Alibaba
        Ant Group
        Alipay
        Baidu
        JD.com
        Meituan
        Pinduoduo
        REDnote
        Bilibili
        Kuaishou
        NetEase
        Xiaomi
        Huawei
        Trip.com Group
        Apple
        Google
        Alphabet
        Microsoft
        Amazon
        AWS
        Meta
        Facebook
        Instagram
        WhatsApp
        YouTube
        Netflix
        NVIDIA
        Tesla
        IBM
        Intel
        AMD
        Qualcomm
        Samsung
        Sony
        Adobe
        Oracle
        Salesforce
        Shopify
        Stripe
        Spotify
        Uber
        Airbnb
        LinkedIn
        Reddit
        Discord
        Zoom
        Dropbox
        Figma
        Canva
        Cloudflare
        """,
        // IELTS 通用、口语、写作与阅读题型
        """
        雅思考试
        雅思学术类
        雅思培训类
        雅思机考
        雅思纸笔考试
        剑桥雅思
        英国文化教育协会
        听力考试
        阅读考试
        写作考试
        口语考试
        口语模考
        雅思考官
        雅思考生
        总分
        单项分
        九分制
        评分标准
        题目册
        答题卡
        话题卡
        一分钟准备
        两分钟陈述
        追问
        流利性与连贯性
        词汇资源
        语法多样性与准确性
        发音
        写作任务一
        写作任务二
        任务完成情况
        任务回应情况
        连贯与衔接
        词汇丰富程度
        概述段
        主题句
        中心论点
        支持论据
        判断题
        选择题
        段落标题配对
        信息配对
        特征配对
        句子结尾配对
        句子填空
        摘要填空
        笔记填空
        表格填空
        流程图填空
        图示标签填空
        简答题
        定位词
        同义替换
        原文依据
        IELTS
        IELTS Academic
        IELTS General Training
        IELTS on Computer
        IELTS on Paper
        British Council
        IDP IELTS
        Cambridge English
        Cambridge IELTS
        Listening
        Reading
        Writing
        Speaking
        band score
        overall band score
        examiner
        candidate
        speaking test
        mock test
        test report form
        Part 1
        Part 2
        Part 3
        cue card
        long turn
        follow-up question
        fluency and coherence
        lexical resource
        grammatical range and accuracy
        pronunciation
        Academic Writing Task 1
        General Training Writing Task 1
        Writing Task 2
        task achievement
        task response
        coherence and cohesion
        overview
        thesis statement
        topic sentence
        supporting evidence
        True False Not Given
        Yes No Not Given
        Multiple Choice
        Matching Headings
        Matching Information
        Matching Features
        Matching Sentence Endings
        Sentence Completion
        Summary Completion
        Note Completion
        Table Completion
        Flow-chart Completion
        Diagram Label Completion
        Short-answer Questions
        ONE WORD ONLY
        NO MORE THAN TWO WORDS
        NO MORE THAN THREE WORDS
        """,
    )

    val defaultWords: List<String> = defaultGroups
        .flatMap { group -> group.trimIndent().lineSequence().map(String::trim).toList() }
        .filter(String::isNotEmpty)
        .distinctBy { it.lowercase(Locale.ROOT) }

    val defaultStorageText: String by lazy { serialize(defaultWords) }
    val defaultEditorText: String by lazy { defaultWords.joinToString("\n") }

    fun parse(raw: String): List<String> {
        val seen = HashSet<String>()
        return raw
            .split(',', '，', '\n', '\r')
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filter { seen.add(it.lowercase(Locale.ROOT)) }
            .toList()
    }

    /**
     * 火山引擎限制热词与上下文合计最多 100 tokens。客户端无法使用服务端 tokenizer，
     * 因此使用保守的 80-token 估算预算，避免默认词表在服务端被无提示截断。
     */
    fun forRequest(
        raw: String,
        priorityRaw: String = "",
        estimatedTokenBudget: Int = REQUEST_TOKEN_BUDGET,
    ): List<String> {
        require(estimatedTokenBudget > 0) { "estimatedTokenBudget must be positive" }
        var remaining = estimatedTokenBudget
        val selected = ArrayList<String>()
        val active = parse(raw)
        val activeKeys = active.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        val priority = parse(priorityRaw).filter { it.lowercase(Locale.ROOT) in activeKeys }
        val priorityKeys = priority.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        (priority + active.filter { it.lowercase(Locale.ROOT) !in priorityKeys }).forEach { word ->
            val cost = estimateTokens(word)
            if (cost <= remaining) {
                selected += word
                remaining -= cost
            }
        }
        return selected
    }

    internal fun estimateTokens(value: String): Int {
        var tokens = 0
        var latinRun = 0
        fun flushLatinRun() {
            if (latinRun > 0) {
                tokens += (latinRun + 2) / 3
                latinRun = 0
            }
        }
        value.forEach { char ->
            when {
                char.isWhitespace() -> flushLatinRun()
                char.code < 128 && (char.isLetterOrDigit() || char in "-_.") -> latinRun += 1
                else -> {
                    flushLatinRun()
                    tokens += 1
                }
            }
        }
        flushLatinRun()
        return tokens.coerceAtLeast(1)
    }

    fun serialize(words: Iterable<String>): String = words.joinToString("，")

    fun normalize(raw: String): String = serialize(parse(raw))

    fun toEditorText(raw: String): String = parse(raw).joinToString("\n")

    fun merge(raw: String, additions: Iterable<String>): List<String> =
        parse((parse(raw) + additions).joinToString("\n"))

    fun prepend(raw: String, additions: Iterable<String>): List<String> =
        parse((additions + parse(raw)).joinToString("\n"))

    /** 旧版会在保存其他设置时顺带写入空热词，升级时需将这种空值迁移为内置词表。 */
    fun migrateLegacy(raw: String?): String =
        if (raw.isNullOrBlank()) defaultStorageText else normalize(raw)

    fun migrateBrandName(raw: String): String = serialize(
        parse(raw).map { if (it.equals("InkTalk", ignoreCase = true)) "inktalk" else it }
    )

    private const val REQUEST_TOKEN_BUDGET = 80
}
