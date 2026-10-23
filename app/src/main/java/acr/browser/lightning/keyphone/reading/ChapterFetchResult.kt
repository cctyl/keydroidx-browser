package acr.browser.lightning.keyphone.reading

/**
 * 取章通道的统一结果（方案 A 原生 HTTP / 方案 B 专属 WebView 共用）。
 *
 * 下游只认这个类型：命中 [Ok] 就交给 `appendChapter`，其余分支各自对应一种"可以重试 /
 * 可以降级 / 真的没有下一章"的语义。**任何失败都不等于"全书读完"** ——
 * 只有 [End] 才允许把 `session.nextUrl` 置空。
 */
sealed class ChapterFetchResult {

    /**
     * 取到一章。
     *
     * @param url 章节 URL（取自实际请求地址）
     * @param title 章节标题（`h1` 优先，退回 `<title>` 的首段）
     * @param html 正文容器 innerHTML（交给 `htmlToPlainStatic` 提纯，与显示层同口径）
     * @param next 下一章 URL；空串 = 这一页没找到「下一章」链接（由宿主判 [End]）
     */
    data class Ok(
        val url: String,
        val title: String,
        val html: String,
        val next: String
    ) : ChapterFetchResult()

    /** 提空：HTTP 200，但正文拿不到 / 太短（多半是 CSR 空壳）——A 走这条降级 B */
    object Empty : ChapterFetchResult()

    /** 网络 / HTTP / 解析异常——**不**算提空，不能污染域名判定缓存 */
    data class Error(val message: String) : ChapterFetchResult()

    /** 确实没有下一章（next 指向自己） */
    object End : ChapterFetchResult()

    /** 通道还没就绪（B 的外壳仍在加载）——延时重试即可 */
    object NotReady : ChapterFetchResult()
}
