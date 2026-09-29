package com.github.eprendre.sources_by_m1ngzer

import com.github.eprendre.tingshu.sources.AudioUrlCustomExtractor
import com.github.eprendre.tingshu.sources.AudioUrlExtraHeaders
import com.github.eprendre.tingshu.sources.AudioUrlExtractor
import com.github.eprendre.tingshu.sources.TingShu
import com.github.eprendre.tingshu.utils.Book
import com.github.eprendre.tingshu.utils.BookDetail
import com.github.eprendre.tingshu.utils.Category
import com.github.eprendre.tingshu.utils.CategoryMenu
import com.github.eprendre.tingshu.utils.CategoryTab
import com.github.eprendre.tingshu.utils.Episode
import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.json.responseJson
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 书音FM (m.mekui.com)
 *
 * 站点是 Next.js 前端 + 帝国CMS(ecmsapi) 后端，所有数据都走 JSON 接口：
 *   分类导航  /ecmsapi/index.php?mod=column&act=navigation&classid=0
 *   书籍列表  /ecmsapi/index.php?mod=movie&act=list&classid=x&page=n&pagesize=20[&sort=onclick desc]
 *   搜索      /ecmsapi/index.php?mod=movie&act=search&keyword=xx&page=n&pagesize=20
 *   书籍详情  /ecmsapi/index.php?mod=movie&act=detail&id=x
 *   章节列表  /ecmsapi/index.php?mod=movie&act=movielist&id=x&page=n&pagesize=500
 *   音频地址  /ecmsapi/index.php?act=wapseries&mod=movie&id=x&movieId=n&t=ts&token=md5
 *
 * 注意：该站不带 User-Agent 会返回 403，所以每个请求都必须带上 UA。
 */
object ShuYinFm : TingShu(), AudioUrlExtraHeaders {

    private const val BASE = "https://m.mekui.com"
    private const val API = "https://m.mekui.com/ecmsapi/index.php"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val TOKEN_KEY = "056a308c515e16b2fe5a5c631319339cbc60a8ee0e03d016"

    private const val PAGE_SIZE = 20
    private const val EPISODE_PAGE_SIZE = 500

    // 分类菜单缓存，避免每次进发现页都重新请求 13 次接口
    private var cachedMenus: List<CategoryMenu>? = null

    override fun getSourceId(): String {
        return "5a642d2d47244ad9b5f6c5c1e801b3c8"
    }

    override fun getUrl(): String {
        return "$BASE/"
    }

    override fun getName(): String {
        return "书音FM"
    }

    override fun getDesc(): String {
        return "推荐指数:5星 ⭐⭐⭐⭐⭐\n有声小说/评书/广播剧/相声小品/戏曲/儿童"
    }

    override fun isSearchable() = true

    override fun isDiscoverable() = true

    override fun isCacheable() = true

    /**
     * 纯接口解析，不需要 WebView
     */
    override fun isWebViewNotRequired() = true

    /**
     * 章节一次性全部拉回来，不需要分页加载
     */
    override fun isMultipleEpisodePages() = false

    // ------------------------------------------------------------------ 工具方法

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun md5(s: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xff))
        }
        return sb.toString()
    }

    private fun fixCover(cover: String): String {
        if (cover.startsWith("http://")) {
            return cover.replaceFirst("http://", "https://")
        }
        return cover
    }

    /**
     * 带 UA 的 GET 请求，返回 JSONObject
     */
    private fun request(params: LinkedHashMap<String, String>): JSONObject {
        val query = params.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
        return Fuel.get("$API?$query")
            .header("User-Agent", UA)
            .header("Referer", "$BASE/")
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .timeout(20000)
            .timeoutRead(20000)
            .responseJson()
            .third.get().obj()
    }

    private fun bookUrl(id: String) = "$API?mod=movie&act=detail&id=$id"

    private fun episodeUrl(workId: String, movieId: String) =
        "$API?act=wapseries&mod=movie&id=$workId&movieId=$movieId"

    private fun catUrl(classId: String, sort: String, page: Int): String {
        val sb = StringBuilder()
        sb.append(API)
            .append("?mod=movie&act=list&classid=").append(classId)
            .append("&page=").append(page)
            .append("&pagesize=").append(PAGE_SIZE)
        if (sort.isNotBlank()) {
            sb.append("&sort=").append(enc(sort))
        }
        return sb.toString()
    }

    /**
     * 接口返回的对象 -> Book
     */
    private fun parseBook(item: JSONObject): Book? {
        val id = item.optString("id")
        if (id.isBlank()) return null
        val title = item.optString("title")
        val cover = fixCover(item.optString("titlepic"))
        val artist = item.optString("player")
        val intro = item.optString("moviesay")
        val status = item.optString("filetype")
        return Book(cover, bookUrl(id), title, "", artist).apply {
            this.intro = intro
            this.status = status
            this.sourceId = getSourceId()
            this.isCompleted = status.contains("完结")
        }
    }

    // ------------------------------------------------------------------ 搜索

    override fun search(keywords: String, page: Int): Pair<List<Book>, Int> {
        val books = ArrayList<Book>()
        var totalPage = 1
        try {
            val params = LinkedHashMap<String, String>()
            params["mod"] = "movie"
            params["act"] = "search"
            params["keyword"] = keywords
            params["page"] = page.toString()
            params["pagesize"] = PAGE_SIZE.toString()

            val root = request(params)
            if (root.optInt("code") == 1) {
                val data = root.optJSONObject("data")
                if (data != null) {
                    totalPage = data.optInt("totalpage", 1)
                    if (totalPage < 1) totalPage = 1
                    val list = data.optJSONArray("list")
                    if (list != null) {
                        for (i in 0 until list.length()) {
                            val item = list.optJSONObject(i) ?: continue
                            parseBook(item)?.let { books.add(it) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return Pair(books, totalPage)
    }

    // ------------------------------------------------------------------ 分类

    override fun getCategoryMenus(): List<CategoryMenu> {
        cachedMenus?.let {
            if (it.size > 1) return it
        }

        val menus = ArrayList<CategoryMenu>()
        // 排行
        menus.add(
            CategoryMenu(
                "排行", listOf(
                    CategoryTab("最新上架", catUrl("0", "", 1)),
                    CategoryTab("最多播放", catUrl("0", "onclick desc", 1))
                )
            )
        )

        try {
            val params = LinkedHashMap<String, String>()
            params["mod"] = "column"
            params["act"] = "navigation"
            params["classid"] = "0"
            val root = request(params)
            val arr = root.optJSONArray("list")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val classId = item.optString("classid")
                    val className = item.optString("classname")
                    if (classId.isBlank() || className.isBlank()) continue

                    val tabs = ArrayList<CategoryTab>()
                    tabs.add(CategoryTab("全部", catUrl(classId, "", 1)))

                    // 有子分类时再请求一次，拿到子分类名称
                    val sons = item.optString("sonclass").split("|").filter { it.isNotBlank() }
                    if (sons.isNotEmpty()) {
                        try {
                            val p = LinkedHashMap<String, String>()
                            p["mod"] = "column"
                            p["act"] = "navigation"
                            p["classid"] = classId
                            val sonRoot = request(p)
                            val sonArr = sonRoot.optJSONArray("list")
                            if (sonArr != null) {
                                for (j in 0 until sonArr.length()) {
                                    val s = sonArr.optJSONObject(j) ?: continue
                                    val sonId = s.optString("classid")
                                    val sonName = s.optString("classname")
                                    if (sonId.isBlank() || sonName.isBlank()) continue
                                    tabs.add(CategoryTab(sonName, catUrl(sonId, "", 1)))
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    menus.add(CategoryMenu(className, tabs))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (menus.size > 1) {
            cachedMenus = menus
        }
        return menus
    }

    override fun getCategoryList(url: String): Category {
        val classId = Regex("classid=(\\d+)").find(url)?.groupValues?.get(1) ?: "0"
        val currentPage = Regex("page=(\\d+)").find(url)?.groupValues?.get(1)?.toInt() ?: 1
        val rawSort = Regex("sort=([^&]*)").find(url)?.groupValues?.get(1) ?: ""

        val books = ArrayList<Book>()
        var totalPage = 1
        try {
            val params = LinkedHashMap<String, String>()
            params["mod"] = "movie"
            params["act"] = "list"
            params["classid"] = classId
            params["page"] = currentPage.toString()
            params["pagesize"] = PAGE_SIZE.toString()
            if (rawSort.isNotBlank()) {
                params["sort"] = URLDecoder.decode(rawSort, "UTF-8")
            }

            val root = request(params)
            if (root.optInt("code") == 1) {
                val data = root.optJSONObject("data")
                if (data != null) {
                    totalPage = data.optInt("totalpage", 1)
                    if (totalPage < 1) totalPage = 1
                    val list = data.optJSONArray("list")
                    if (list != null) {
                        for (i in 0 until list.length()) {
                            val item = list.optJSONObject(i) ?: continue
                            parseBook(item)?.let { books.add(it) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val nextUrl = if (currentPage < totalPage) {
            url.replace(Regex("page=\\d+"), "page=${currentPage + 1}")
        } else ""
        return Category(books, currentPage, totalPage, url, nextUrl)
    }

    // ------------------------------------------------------------------ 详情/章节

    override fun getBookDetailInfo(
        bookUrl: String,
        loadEpisodes: Boolean,
        loadFullPages: Boolean
    ): BookDetail {
        val id = Regex("[?&]id=(\\d+)").find(bookUrl)?.groupValues?.get(1)
            ?: return BookDetail(emptyList())

        var cover = ""
        var intro = ""
        var artist = ""

        // 详情：封面 / 简介 / 播音
        try {
            val params = LinkedHashMap<String, String>()
            params["mod"] = "movie"
            params["act"] = "detail"
            params["id"] = id
            val detail = request(params).optJSONObject("data")?.optJSONObject("detail")
            if (detail != null) {
                cover = fixCover(detail.optString("titlepic"))
                intro = detail.optString("moviesay")
                artist = detail.optString("player")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 章节列表
        val episodes = ArrayList<Episode>()
        var episodesCount = 0
        if (loadEpisodes) {
            try {
                var page = 1
                var totalPage = 1
                do {
                    val params = LinkedHashMap<String, String>()
                    params["mod"] = "movie"
                    params["act"] = "movielist"
                    params["id"] = id
                    params["page"] = page.toString()
                    params["pagesize"] = EPISODE_PAGE_SIZE.toString()

                    val data = request(params).optJSONObject("data")
                    if (data != null) {
                        episodesCount = data.optInt("count", episodesCount)
                        totalPage = data.optInt("totalpage", 1)
                        if (totalPage < 1) totalPage = 1
                        val arr = data.optJSONArray("moielist")
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val item = arr.optJSONObject(i) ?: continue
                                val movieId = item.optString("id")
                                val title = item.optString("title")
                                if (movieId.isBlank()) continue
                                episodes.add(Episode(title, episodeUrl(id, movieId)))
                            }
                        }
                    }
                    page++
                } while (loadFullPages && page <= totalPage)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return BookDetail(episodes, intro, artist, "", episodesCount, cover)
    }

    // ------------------------------------------------------------------ 音频地址

    override fun getAudioUrlExtractor(): AudioUrlExtractor {
        AudioUrlCustomExtractor.setUp { url ->
            extractAudioUrl(url)
        }
        return AudioUrlCustomExtractor
    }

    private fun extractAudioUrl(url: String): String {
        return try {
            val workId = Regex("[?&]id=(\\d+)").find(url)?.groupValues?.get(1) ?: return ""
            val movieId = Regex("[?&]movieId=(\\d+)").find(url)?.groupValues?.get(1) ?: return ""
            val ts = System.currentTimeMillis() / 1000

            // 签名：参数按 key 升序拼接，末尾追加 token=密钥，整体取 md5
            val raw = "act=wapseries&id=$workId&mod=movie&movieId=$movieId&t=$ts"
            val token = md5("$raw&token=$TOKEN_KEY")

            val root = Fuel.get("$API?$raw&token=$token")
                .header("User-Agent", UA)
                .header("Referer", "$BASE/")
                .header("Accept", "application/json, text/plain, */*")
                .timeout(20000)
                .timeoutRead(20000)
                .responseJson()
                .third.get().obj()

            if (root.optInt("code") != 1) return ""
            val data = root.optJSONObject("data") ?: return ""

            var audio = data.optString("SeriesUrl")
            if (audio.isBlank()) {
                val arr = data.optJSONArray("signed_urls")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i)
                        if (s.isNotBlank()) {
                            audio = s
                            break
                        }
                    }
                }
            }
            if (audio.isBlank()) return ""

            // SeriesUrl 是 302 跳转地址，这里手动解析成真实音频直链，解析不了就原样返回
            resolveRedirect(audio)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    /**
     * 手动跟随最多 3 次跳转，拿到最终的音频直链
     */
    private fun resolveRedirect(startUrl: String): String {
        var current = startUrl
        for (i in 0..2) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(current).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.requestMethod = "GET"
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", UA)
                conn.setRequestProperty("Referer", "$BASE/")
                val code = conn.responseCode
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                conn = null
                if (code in 300..399 && !location.isNullOrBlank()) {
                    current = URL(URL(current), location).toString()
                } else {
                    return current
                }
            } catch (e: Exception) {
                return current
            } finally {
                try {
                    conn?.disconnect()
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
        return current
    }

    /**
     * 请求音频时带上 UA，避免被拦截
     */
    override fun headers(audioUrl: String): Map<String, String> {
        return if (audioUrl.contains("aikeu.com") || audioUrl.contains("kuwo.cn")) {
            mapOf(
                "User-Agent" to UA,
                "Referer" to "$BASE/"
            )
        } else {
            emptyMap()
        }
    }
}
