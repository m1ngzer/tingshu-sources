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
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/**
 * 听友听书（www.tingyou.fm）
 *
 * 站点是 Nuxt 前端，数据全部走站点自己代理的 JSON 接口（/listening-api 反向代理到后端）：
 *   分类筛选  GET  /listening-api/filters                      -> {sorts[], statuses[], categories[{id,name,types[]}]}
 *   分类列表  GET  /listening-api/category?category=&type=&sort=&status=&page=  -> {data|items, page, pages}
 *   搜索      POST /listening-api/search  {keyword, page}      -> {results|items, has_more}
 *   详情      GET  /listening-api/album/{id}                   -> {id,title,author,teller,cat,cover_url,synopsis|description|intro,count,status}
 *   章节      GET  /listening-api/chapters/{id}                -> {chapters:[{index,title,duration,artist}]} 一次全量返回
 *   播放      POST /listening-api/play    {album_id, chapter_idx} -> {play_url}（签名 m4a 直链）
 *
 * 注意：
 * - 每个请求必须带 x-listening-session（随机 UUID v4，会话级复用）和 x-listening-timezone。
 * - 分类列表 pages 服务端封顶 100 页。
 * - 音频是 m4a（ftypM4A），支持 Range，ExoPlayer 直接能播。
 */
object TingYouFm : TingShu(), AudioUrlExtraHeaders {

    private const val BASE = "https://www.tingyou.fm"
    private const val API = "$BASE/listening-api"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** 会话 ID，接口要求必须是 UUID v4 格式，生成一次复用即可 */
    private val sessionId: String by lazy { UUID.randomUUID().toString() }

    // 分类菜单缓存，filters 接口一次拿全，没必要反复请求
    private var cachedMenus: List<CategoryMenu>? = null

    override fun getSourceId(): String = "e898c34fe8fa4956923b362f0b8dd4dd"

    override fun getUrl(): String = "$BASE/listening"

    override fun getName(): String = "听友听书"

    override fun getDesc(): String = "推荐指数:5星 ⭐⭐⭐⭐⭐\n有声小说/相声小品/百家讲坛/历史军事/言情"

    override fun isSearchable() = true

    override fun isDiscoverable() = true

    override fun isCacheable() = true

    /** 纯 JSON 接口，不需要 WebView */
    override fun isWebViewNotRequired() = true

    /** 章节接口一次全量返回，不需要分页加载 */
    override fun isMultipleEpisodePages() = false

    // ------------------------------------------------------------------ 工具方法

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun dec(s: String): String = URLDecoder.decode(s, "UTF-8")

    private fun fixCover(cover: String): String {
        if (cover.startsWith("http://")) {
            return cover.replaceFirst("http://", "https://")
        }
        return cover
    }

    private fun getJson(path: String): JSONObject {
        return Fuel.get("$API/$path")
            .header("User-Agent", UA)
            .header("Referer", "$BASE/listening")
            .header("Accept", "application/json")
            .header("x-listening-session", sessionId)
            .header("x-listening-timezone", "Asia/Shanghai")
            .timeout(20000)
            .timeoutRead(20000)
            .responseJson()
            .third.get().obj()
    }

    private fun postJson(path: String, body: JSONObject): JSONObject {
        return Fuel.post("$API/$path")
            .header("User-Agent", UA)
            .header("Referer", "$BASE/listening")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("x-listening-session", sessionId)
            .header("x-listening-timezone", "Asia/Shanghai")
            .body(body.toString())
            .timeout(20000)
            .timeoutRead(20000)
            .responseJson()
            .third.get().obj()
    }

    private fun bookUrl(id: String) = "$BASE/listening/album/$id"

    private fun episodeUrl(albumId: String, chapterIdx: Int) =
        "$API/play?album_id=$albumId&chapter_idx=$chapterIdx"

    private fun catUrl(category: String, type: String, sort: String, page: Int): String {
        val sb = StringBuilder("$API/category?page=").append(page)
        if (category.isNotBlank()) sb.append("&category=").append(category)
        if (type.isNotBlank()) sb.append("&type=").append(type)
        if (sort.isNotBlank()) sb.append("&sort=").append(sort)
        return sb.toString()
    }

    /**
     * 接口返回的专辑对象 -> Book
     * 字段：id, title, author, teller, cat, cover_url, synopsis/description/intro, count, status(1=连载)
     */
    private fun parseBook(item: JSONObject): Book? {
        val id = item.optString("id").ifBlank { item.optString("album_id") }
        if (id.isBlank()) return null
        val title = item.optString("title", "未命名专辑")
        val cover = fixCover(item.optString("cover_url"))
        val author = item.optString("author")
        val teller = item.optString("teller")
        val intro = listOf(
            item.optString("synopsis"),
            item.optString("description"),
            item.optString("intro")
        ).filter { it.isNotBlank() }.maxByOrNull { it.length } ?: ""
        val status = if (item.optInt("status") == 1) "连载中" else "已完结"
        return Book(cover, bookUrl(id), title, author, teller).apply {
            this.intro = intro
            this.status = status
            this.sourceId = getSourceId()
            this.isCompleted = status == "已完结"
        }
    }

    /** items 数组可能在 data 或 items 字段下 */
    private fun optItems(root: JSONObject) =
        root.optJSONArray("data") ?: root.optJSONArray("items") ?: root.optJSONArray("results")

    // ------------------------------------------------------------------ 搜索

    override fun search(keywords: String, page: Int): Pair<List<Book>, Int> {
        val books = ArrayList<Book>()
        var totalPage = page
        try {
            val body = JSONObject()
                .put("keyword", keywords)
                .put("page", page)
            val root = postJson("search", body)
            val arr = root.optJSONArray("results") ?: root.optJSONArray("items")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    parseBook(item)?.let { books.add(it) }
                }
            }
            // 接口只给 has_more，没有总页数；有下一页就报 page+1
            if (root.optBoolean("has_more") && books.isNotEmpty()) {
                totalPage = page + 1
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
        // 排行：全库按不同维度排序
        menus.add(
            CategoryMenu(
                "排行", listOf(
                    CategoryTab("播放最多", catUrl("", "", "popular", 1)),
                    CategoryTab("最近更新", catUrl("", "", "updated", 1)),
                    CategoryTab("最新发布", catUrl("", "", "new", 1)),
                    CategoryTab("综合推荐", catUrl("", "", "comprehensive", 1))
                )
            )
        )

        try {
            val root = getJson("filters")
            val cats = root.optJSONArray("categories")
            if (cats != null) {
                for (i in 0 until cats.length()) {
                    val cat = cats.optJSONObject(i) ?: continue
                    val catId = cat.optString("id")
                    val catName = cat.optString("name")
                    if (catId.isBlank() || catName.isBlank()) continue

                    val tabs = ArrayList<CategoryTab>()
                    tabs.add(CategoryTab("全部", catUrl(catId, "", "", 1)))
                    val types = cat.optJSONArray("types")
                    if (types != null) {
                        for (j in 0 until types.length()) {
                            val t = types.optJSONObject(j) ?: continue
                            val typeId = t.optString("id")
                            val typeName = t.optString("name")
                            if (typeId.isBlank() || typeName.isBlank()) continue
                            tabs.add(CategoryTab(typeName, catUrl(catId, typeId, "", 1)))
                        }
                    }
                    menus.add(CategoryMenu(catName, tabs))
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
        fun param(name: String): String =
            Regex("[?&]$name=([^&]*)").find(url)?.groupValues?.get(1)?.let { dec(it) } ?: ""

        val category = param("category")
        val type = param("type")
        val sort = param("sort")
        val status = param("status")
        val currentPage = param("page").toIntOrNull() ?: 1

        val books = ArrayList<Book>()
        var totalPage = 1
        try {
            val sb = StringBuilder("category?page=").append(currentPage)
            if (category.isNotBlank()) sb.append("&category=").append(enc(category))
            if (type.isNotBlank()) sb.append("&type=").append(enc(type))
            if (sort.isNotBlank()) sb.append("&sort=").append(enc(sort))
            if (status.isNotBlank()) sb.append("&status=").append(enc(status))

            val root = getJson(sb.toString())
            totalPage = root.optInt("pages", 1)
            if (totalPage < 1) totalPage = 1
            val arr = optItems(root)
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    parseBook(item)?.let { books.add(it) }
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
        val id = Regex("/album/(\\d+)").find(bookUrl)?.groupValues?.get(1)
            ?: return BookDetail(emptyList())

        var cover = ""
        var intro = ""
        var artist = ""
        var author = ""
        var episodesCount = 0

        // 详情：封面 / 简介 / 播音 / 作者
        try {
            val detail = getJson("album/$id")
            cover = fixCover(detail.optString("cover_url"))
            artist = detail.optString("teller")
            author = detail.optString("author")
            episodesCount = detail.optInt("count", 0)
            intro = listOf(
                detail.optString("synopsis"),
                detail.optString("description"),
                detail.optString("intro")
            ).filter { it.isNotBlank() }.maxByOrNull { it.length } ?: ""
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 章节：一次全量返回，按 index 排序
        val episodes = ArrayList<Episode>()
        if (loadEpisodes) {
            try {
                val root = getJson("chapters/$id")
                val arr = root.optJSONArray("chapters")
                if (arr != null) {
                    val list = ArrayList<Pair<Int, Episode>>()
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val idx = item.optInt("index", 0)
                        if (idx <= 0) continue
                        val title = item.optString("title").ifBlank { "第 $idx 集" }
                        list.add(idx to Episode(title, episodeUrl(id, idx)))
                    }
                    list.sortBy { it.first }
                    episodes.addAll(list.map { it.second })
                    if (episodesCount <= 0) episodesCount = episodes.size
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return BookDetail(episodes, intro, artist, author, episodesCount, cover)
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
            val albumId = Regex("[?&]album_id=(\\d+)").find(url)?.groupValues?.get(1) ?: return ""
            val chapterIdx = Regex("[?&]chapter_idx=(\\d+)").find(url)?.groupValues?.get(1) ?: return ""

            val body = JSONObject()
                .put("album_id", albumId)
                .put("chapter_idx", chapterIdx.toInt())
            val root = postJson("play", body)
            fixCover(root.optString("play_url"))
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    /**
     * 请求音频时带上 UA，避免 CDN 拦截
     */
    override fun headers(audioUrl: String): Map<String, String> {
        return mapOf("User-Agent" to UA)
    }
}
