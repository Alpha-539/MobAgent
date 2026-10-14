package com.mobagent.app.agent

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Agent uchun internet asboblari: `web_search` (qidiruv) va `web_fetch` (sahifa matni).
 *
 * Nega `curl` emas: Ubuntu Base'da curl yo'q, o'rnatish uchun avval `apt-get update` kerak,
 * Linux o'chiq bo'lsa umuman ishlamaydi, ko'p saytlar (Cloudflare) "curl" so'rovini bot deb
 * to'sadi. Bu asboblar ilovaning o'zida (HttpURLConnection) ishlaydi, brauzerga o'xshash
 * sarlavhalar yuboradi va natijani modelga toza matn qilib beradi.
 */
object WebTools {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"
    private const val MAX_BYTES = 1_500_000
    private const val MAX_TEXT_CHARS = 9_000
    private const val MAX_RESULTS = 8

    private class Hit(val title: String, val url: String, val snippet: String)

    // ---------------------------------------------------------------- web_search

    fun search(query: String): ExecutionResult {
        val q = query.trim()
        if (q.isEmpty()) return ExecutionResult(0, "Search query is empty. Ask the user what to look up, or answer from your own knowledge.")
        val engines = listOf<Pair<String, (String) -> List<Hit>>>(
            "DuckDuckGo" to { x: String -> duckDuckGo(x) },
            "Bing" to { x: String -> bing(x) },
            "DuckDuckGo Lite" to { x: String -> duckDuckGoLite(x) },
            "Mojeek" to { x: String -> mojeek(x) }
        )
        // Har bir qidiruv tizimi ketma-ket sinaladi; vaqtinchalik xato (limit, tarmoq) bo'lsa bitta qayta urinish.
        for (attempt in 0..1) {
            for ((_, run) in engines) {
                try {
                    val hits = run(q)
                    if (hits.isNotEmpty()) return ExecutionResult(0, formatHits(q, hits))
                } catch (e: Exception) {
                    // keyingi tizimga o'tamiz
                }
            }
            if (attempt == 0) try { Thread.sleep(700) } catch (_: InterruptedException) { }
        }
        // Hech biri javob bermadi: xato EMAS (exit 0, "Error" so'zisiz) — model foydalanuvchiga xato ko'rsatmay,
        // o'z bilimi bilan javob beradi va qidiruvni qayta-qayta takrorlamaydi.
        return ExecutionResult(
            0,
            "No live web results are available right now (search engines did not respond). " +
                "Do NOT call web_search again for this question and do NOT mention any error or failure to the user. " +
                "Answer from your own knowledge as well as you can; if the topic may have changed recently, " +
                "add one short note that the information may not be fully up to date."
        )
    }

    private fun formatHits(q: String, hits: List<Hit>): String = buildString {
        append("Search results for: ").append(q).append("\n\n")
        hits.take(MAX_RESULTS).forEachIndexed { i, h ->
            append(i + 1).append(". ").append(h.title).append('\n')
            append("   ").append(h.url).append('\n')
            if (h.snippet.isNotBlank()) append("   ").append(h.snippet).append('\n')
            append('\n')
        }
        append("Use web_fetch with a result URL to read the full page. Cite the source URLs in your answer.")
    }

    private fun duckDuckGo(q: String): List<Hit> {
        val html = http(
            "https://html.duckduckgo.com/html/",
            post = "q=" + URLEncoder.encode(q, "UTF-8") + "&kl=wt-wt"
        )
        if (html.contains("anomaly-modal") || html.contains("g-recaptcha")) error("bot tekshiruvi (captcha)")
        val link = Regex("""<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snip = Regex("""class="result__snippet"[^>]*>(.*?)</(?:a|td|div)>""", RegexOption.DOT_MATCHES_ALL)
        val snippets = snip.findAll(html).map { clean(it.groupValues[1]) }.toList()
        return link.findAll(html).mapIndexedNotNull { i, m ->
            val url = unwrapDdg(unescape(m.groupValues[1]))
            if (!url.startsWith("http") || "duckduckgo.com/y.js" in url) return@mapIndexedNotNull null // reklama
            Hit(clean(m.groupValues[2]), url, snippets.getOrElse(i) { "" })
        }.toList()
    }

    private fun unwrapDdg(href: String): String {
        val u = if (href.startsWith("//")) "https:$href" else href
        val m = Regex("""[?&]uddg=([^&]+)""").find(u) ?: return u
        return try { URLDecoder.decode(m.groupValues[1], "UTF-8") } catch (e: Exception) { u }
    }

    private fun bing(q: String): List<Hit> {
        val html = http("https://www.bing.com/search?q=" + URLEncoder.encode(q, "UTF-8") + "&setlang=en")
        val block = Regex("""<li class="b_algo".*?</li>""", RegexOption.DOT_MATCHES_ALL)
        val a = Regex("""<h2[^>]*>\s*<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val p = Regex("""<p[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
        return block.findAll(html).mapNotNull { b ->
            val am = a.find(b.value) ?: return@mapNotNull null
            val url = unwrapBing(unescape(am.groupValues[1]))
            if (!url.startsWith("http")) return@mapNotNull null
            Hit(clean(am.groupValues[2]), url, p.find(b.value)?.let { clean(it.groupValues[1]) }.orEmpty())
        }.toList()
    }

    private fun duckDuckGoLite(q: String): List<Hit> {
        val html = http("https://lite.duckduckgo.com/lite/", post = "q=" + URLEncoder.encode(q, "UTF-8") + "&kl=wt-wt")
        if (html.contains("anomaly-modal") || html.contains("g-recaptcha")) error("bot tekshiruvi (captcha)")
        val link = Regex("""<a[^>]+class=['"]result-link['"][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val href = Regex("""href=['"]([^'"]+)['"]""")
        val snip = Regex("""class=['"]result-snippet['"][^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)
        val snippets = snip.findAll(html).map { clean(it.groupValues[1]) }.toList()
        return link.findAll(html).mapIndexedNotNull { i, m ->
            val h = href.find(m.value)?.groupValues?.get(1) ?: return@mapIndexedNotNull null
            val url = unwrapDdg(unescape(h))
            if (!url.startsWith("http") || "duckduckgo.com/y.js" in url) return@mapIndexedNotNull null
            Hit(clean(m.groupValues[1]), url, snippets.getOrElse(i) { "" })
        }.toList()
    }

    private fun mojeek(q: String): List<Hit> {
        val html = http("https://www.mojeek.com/search?q=" + URLEncoder.encode(q, "UTF-8"))
        val block = Regex("""<li[^>]*>.*?</li>""", RegexOption.DOT_MATCHES_ALL)
        val a = Regex("""<a[^>]+class=['"][^'"]*\btitle\b[^'"]*['"][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val href = Regex("""href=['"](https?://[^'"]+)['"]""")
        val p = Regex("""<p[^>]*class=['"]s['"][^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
        return block.findAll(html).mapNotNull { b ->
            val am = a.find(b.value) ?: return@mapNotNull null
            val url = href.find(am.value)?.groupValues?.get(1)?.let { unescape(it) } ?: return@mapNotNull null
            Hit(clean(am.groupValues[1]), url, p.find(b.value)?.let { clean(it.groupValues[1]) }.orEmpty())
        }.toList()
    }

    /** Bing ba'zan havolani `bing.com/ck/a?...&u=a1<base64url>` ko'rinishida o'rab beradi. */
    private fun unwrapBing(href: String): String {
        if ("bing.com/ck/a" !in href) return href
        val enc = Regex("""[?&]u=a1([^&]+)""").find(href)?.groupValues?.get(1) ?: return href
        return try {
            String(android.util.Base64.decode(enc, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING))
        } catch (e: Exception) { href }
    }

    // ---------------------------------------------------------------- web_fetch

    /** Kichik modellar yozgan buzuq manzillarni tuzatadi: "https,//x", "https://https://x", qo'shtirnoq/bo'shliq. */
    private fun normalizeUrl(raw: String): String {
        var u = raw.trim().trim('"', '\'', '`', '<', '>', ' ')
        u = u.replace(Regex("^(https?)[,;.]//", RegexOption.IGNORE_CASE), "$1://")
        while (Regex("^https?://https?[:,;.]?//", RegexOption.IGNORE_CASE).containsMatchIn(u)) {
            u = u.replace(Regex("^https?://(?=https?[:,;.]?//)", RegexOption.IGNORE_CASE), "")
            u = u.replace(Regex("^(https?)[,;.]//", RegexOption.IGNORE_CASE), "$1://")
        }
        return u
    }

    fun fetch(rawUrl: String): ExecutionResult {
        var url = normalizeUrl(rawUrl)
        if (url.isEmpty()) return ExecutionResult(0, "The URL is empty. Ask the user for the page address, or answer from your own knowledge.")
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        // Ichki tarmoq manzillari xavfsizlik uchun doim taqiqlanadi.
        try { checkPublicHost(url) } catch (e: Exception) {
            return ExecutionResult(1, "Xato: $url ochilmadi (${e.message ?: e.javaClass.simpleName})")
        }
        // 1) to'g'ridan-to'g'ri (bitta qayta urinish bilan); 2) bot himoyasi / JavaScript sahifa bo'lsa o'qish xizmati orqali.
        var text = ""
        for (attempt in 0..1) {
            try {
                text = extractText(http(url))
                if (text.isNotBlank()) break
            } catch (e: Exception) { /* qayta urinamiz */ }
            if (attempt == 0) try { Thread.sleep(600) } catch (_: InterruptedException) { }
        }
        if (text.isBlank()) {
            try { text = extractText(http("https://r.jina.ai/$url")) } catch (e: Exception) { /* pastda */ }
        }
        if (text.isBlank()) {
            return ExecutionResult(
                0,
                "The page $url could not be opened right now. Do NOT mention any error to the user and do NOT retry " +
                    "the same URL. Use web_search for another source, or answer from your own knowledge."
            )
        }
        val cut = text.length > MAX_TEXT_CHARS
        return ExecutionResult(
            0,
            "URL: $url\n\n" + text.take(MAX_TEXT_CHARS) +
                if (cut) "\n\n[truncated: page is longer, only the first $MAX_TEXT_CHARS characters are shown]" else ""
        )
    }

    private fun extractText(body: String): String {
        val isHtml = body.contains("<html", ignoreCase = true) || body.contains("<body", ignoreCase = true) ||
            body.contains("<!doctype html", ignoreCase = true)
        return if (isHtml) htmlToText(body) else body.trim()
    }

    /** Telefon tarmog'idagi ichki manzillarga (192.168.x.x, localhost...) so'rov yuborilmasin. */
    private fun checkPublicHost(url: String) {
        val host = URL(url).host
        val addr = InetAddress.getByName(host)
        if (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isAnyLocalAddress) {
            error("ichki tarmoq manzillari taqiqlangan")
        }
    }

    // ---------------------------------------------------------------- HTTP / HTML

    private fun http(url: String, post: String? = null, redirects: Int = 0): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 12_000
            c.readTimeout = 20_000
            c.instanceFollowRedirects = false // https<->http va ichki manzilga yo'naltirishni o'zimiz tekshiramiz
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8,*/*;q=0.5")
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9,ru;q=0.6,uz;q=0.5")
            if (post != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(post.toByteArray()) }
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: error("HTTP $code (yo'naltirish manzili yo'q)")
                if (redirects >= 5) error("juda ko'p yo'naltirish")
                val next = URL(URL(url), loc).toString()
                checkPublicHost(next)
                return http(next, null, redirects + 1)
            }
            if (code == 403 || code == 429 || code == 503) error("HTTP $code (sayt so'rovni to'sdi: bot himoyasi yoki limit)")
            if (code !in 200..299) error("HTTP $code")
            val bytes = c.inputStream.use { ins ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (out.size() < MAX_BYTES) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            val charset = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE)
                .find(c.contentType.orEmpty())?.groupValues?.get(1)
            return try { String(bytes, charset(charset ?: "UTF-8")) } catch (e: Exception) { String(bytes, Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    private fun htmlToText(html: String): String {
        var s = html
        s = Regex("(?is)<(script|style|noscript|svg|head|nav|footer|form|iframe)\\b.*?</\\1>").replace(s, " ")
        s = Regex("(?s)<!--.*?-->").replace(s, " ")
        s = Regex("(?i)</?(p|div|br|li|tr|h[1-6]|section|article|ul|ol|table|blockquote)[^>]*>").replace(s, "\n")
        s = Regex("<[^>]+>").replace(s, " ")
        s = unescape(s)
        s = s.lines().joinToString("\n") { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
        return Regex("\\n{3,}").replace(s, "\n\n").trim()
    }

    private fun clean(s: String): String =
        unescape(Regex("<[^>]+>").replace(s, "")).replace(Regex("\\s+"), " ").trim()

    private fun unescape(s: String): String {
        var r = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'").replace("&nbsp;", " ")
            .replace("&apos;", "'")
        r = Regex("&#(\\d+);").replace(r) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        return r
    }
}
