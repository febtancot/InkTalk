package com.inktalk.ime.asr

/**
 * 开始请求发出后尚未发送音频，因此首个成功的非结束响应即可确认服务端已接受请求。
 * 不把 payload sequence 固定为 1：不同端点或协议版本可能返回 1、0、-1 或不带序号。
 */
object AsrStartConfirmation {
    fun accepts(response: SaucProtocol.ServerMessage.Response): Boolean = !response.isLast
}
