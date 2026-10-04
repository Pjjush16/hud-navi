/*
 * WebSearchClient.kt - 全球可用免 Key 网页搜索 (v11.0)
 *
 * 双引擎架构：
 *   1. SearXNG 公共实例（主引擎）— 聚合 70+ 搜索引擎，JSON API
 *   2. Bing HTML Scraping（兜底引擎）— 全球可用
 *
 * 所有搜索均免费、无需 API Key、全球可用。
 */

package com.hud.navi

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 搜索结果条目
 */
data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String
)

/**
 * 搜索响应
 */
data class SearchResponse(
    val query: String,
    val results: List<SearchResult>,
    val engine: String,       // 使用哪个引擎
    val success: Boolean,
    val error: String? = null
) {
    /**
     * 将搜索结果格式化为适合 TTS 播报的摘要文本
     */
    fun toSummary(maxResults: Int = 3): String {
        if (!success || results.isEmpty()) {
            return "抱歉，没有找到关于${query}的相关信息。"
        }

        val topResults = results.take(maxResults)
        return buildString {
            append("关于${query}，找到以下信息：")
            topResults.forEachIndexed { idx, result ->
                append("\n${idx + 1}. ${result.title}。${result.snippet}")
            }
        }
    }

    /**
     * 将搜索结果格式化为适合 LLM 消费的上下文
     */
    fun toContext(maxResults: Int = 5): String {
        if (!success || results.isEmpty()) return ""

        return results.take(maxResults).joinToString("\n\n") { r ->
            "标题: ${r.title}\n摘要: ${r.snippet}\n来源: ${r.url}"
        }
    }
}

class WebSearchClient {

    companion object {
        private const val TAG = "WebSearch"
        private const val TIMEOUT = 8000

        /**
         * SearXNG 公共实例列表（按可靠性排序）
         *
         * 这些是社区维护的公共实例，支持 JSON API。
         * 如果某个实例不可用，自动尝试下一个。
         * 列表来源：https://searx.space/
         */
        private val SEARXNG_INSTANCES = listOf(
            "https://searx.be",
            "https://search.bus-hit.me",
            "https://searxng.site",
            "https://search.ononoki.org",
            "https://searx.tiekoetter.com",
            "https://search.rhscz.eu",
            "https://paulgo.io",
            "https://s.zhaocloud.net"    // 国内可能可达
        )

        private const val BING_URL = "https://www.bing.com/search"
    }

    /**
     * 执行搜索 — 先尝试 SearXNG，失败自动降级 Bing
     *
     * @param query 搜索关键词
     * @param maxResults 最大返回结果数
     * @return SearchResponse 搜索结果
     */
    fun search(query: String, maxResults: Int = 5): SearchResponse {
        // 策略1：SearXNG
        val searxngResult = searchSearXNG(query, maxResults)
        if (searxngResult != null && searxngResult.success) {
            return searxngResult
        }

        // 策略2：Bing
        Log.i(TAG, "SearXNG failed, falling back to Bing")
        val bingResult = searchBing(query, maxResults)
        if (bingResult != null && bingResult.success) {
            return bingResult
        }

        // 全部失败
        return SearchResponse(
            query = query,
            results = emptyList(),
            engine = "none",
            success = false,
            error = "所有搜索引擎均不可用"
        )
    }

    // ==================== SearXNG ====================

    /**
     * SearXNG JSON API 搜索
     *
     * API 格式：GET /search?q=QUERY&format=json&categories=general&language=zh-CN
     *
     * 响应格式：
     * {
     *   "results": [
     *     {
     *       "title": "...",
     *       "url": "...",
     *       "content": "..."
     *     }
     *   ]
     * }
     */
    private fun searchSearXNG(query: String, maxResults: Int): SearchResponse? {
        for (instance in SEARXNG_INSTANCES) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val urlStr = "$instance/search?q=$encodedQuery&format=json&categories=general&language=zh-CN"
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = TIMEOUT
                conn.readTimeout = TIMEOUT
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "HudNavi/11.0")
                conn.setRequestProperty("Accept", "application/json")

                if (conn.responseCode == 200) {
                    val body = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                    conn.disconnect()

                    val json = JSONObject(body)
                    val resultsArray = json.optJSONArray("results") ?: JSONArray()
                    val results = mutableListOf<SearchResult>()

                    for (i in 0 until minOf(resultsArray.length(), maxResults)) {
                        val item = resultsArray.getJSONObject(i)
                        results.add(SearchResult(
                            title = item.optString("title", ""),
                            url = item.optString("url", ""),
                            snippet = item.optString("content", "").take(200)
                        ))
                    }

                    if (results.isNotEmpty()) {
                        Log.i(TAG, "SearXNG ($instance): ${results.size} results")
                        return SearchResponse(query, results, "searxng", true)
                    }
                } else {
                    Log.w(TAG, "SearXNG ($instance): HTTP ${conn.responseCode}")
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "SearXNG ($instance) failed: ${e.message}")
            }
        }
        return null
    }

    // ==================== Bing ====================

    /**
     * Bing HTML 抓取搜索
     *
     * 请求 Bing 搜索页面，解析 HTML 提取搜索结果。
     * 使用移动版 UA 获取更简洁的 HTML，便于解析。
     */
    private fun searchBing(query: String, maxResults: Int): SearchResponse? {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val urlStr = "$BING_URL?q=$encodedQuery&setlang=zh-CN&count=$maxResults"
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT
            conn.requestMethod = "GET"
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            conn.instanceFollowRedirects = true

            if (conn.responseCode == 200) {
                val html = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                conn.disconnect()

                val results = parseBingHtml(html, maxResults)
                if (results.isNotEmpty()) {
                    Log.i(TAG, "Bing: ${results.size} results")
                    return SearchResponse(query, results, "bing", true)
                }
            } else {
                Log.w(TAG, "Bing: HTTP ${conn.responseCode}")
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bing failed: ${e.message}")
        }
        return null
    }

    /**
     * 解析 Bing HTML 搜索结果
     *
     * Bing 搜索结果 HTML 结构（移动版）：
     * <li class="b_algo">
     *   <h2><a href="URL">TITLE</a></h2>
     *   <p class="b_lineclamp...">SNIPPET</p>
     * </li>
     */
    private fun parseBingHtml(html: String, maxResults: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        // 提取 b_algo 块
        val algoPattern = Regex("""<li class="b_algo">(.*?)</li>""", RegexOption.DOT_MATCHES_ALL)
        val linkPattern = Regex("""<a\s+href="([^"]*)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snippetPattern = Regex("""<p[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
        val tagPattern = Regex("""<[^>]+>""")

        for (match in algoPattern.findAll(html)) {
            if (results.size >= maxResults) break

            val block = match.groupValues[1]

            // 提取标题和链接
            val linkMatch = linkPattern.find(block) ?: continue
            val url = linkMatch.groupValues[1].trim()
            val rawTitle = linkMatch.groupValues[2]
            val title = tagPattern.replace(rawTitle, "").trim()

            // 提取摘要
            val snippetMatch = snippetPattern.find(block)
            val rawSnippet = snippetMatch?.groupValues?.get(1) ?: ""
            val snippet = tagPattern.replace(rawSnippet, "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .trim()
                .take(200)

            if (title.isNotBlank() && url.startsWith("http")) {
                results.add(SearchResult(title, url, snippet))
            }
        }

        return results
    }
}
