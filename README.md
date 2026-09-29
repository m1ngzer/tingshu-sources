# 我的听书 · 自定义源

[书音FM](https://m.mekui.com) 有声小说 / 评书 / 广播剧 / 相声小品 / 戏曲 / 儿童。

## 订阅方式

App 内：**源管理 → 右上角「添加」→ 填入下面这个地址**

```
https://cdn.jsdelivr.net/gh/m1ngzer/tingshu-sources@main/external_sources.json
```

> 直连不稳的话换成 raw：
> `https://raw.githubusercontent.com/m1ngzer/tingshu-sources/main/external_sources.json`
>
> jsdelivr 有缓存，更新后可能要等几分钟才生效。

## 目录

| 文件 | 说明 |
|---|---|
| `external_sources.json` | 订阅描述文件，App 读的就是它 |
| `sources_by_m1ngzer.jar` | 编译好的源包（已转 dex） |
| `src/` | Kotlin 源码 |
| `tools/verify.py` | 全链路自检脚本，改版后定位哪一步挂了 |

## 站点接口备忘

站点是 Next.js 前端 + 帝国CMS `/ecmsapi/index.php` JSON 后端，纯接口解析，不需要 WebView / 登录。

| 功能 | 接口 |
|---|---|
| 分类导航 | `mod=column&act=navigation&classid=0` |
| 子分类 | `mod=column&act=navigation&classid={父分类id}` |
| 分类列表 | `mod=movie&act=list&classid=x&page=n&pagesize=20[&sort=onclick desc]` |
| 搜索 | `mod=movie&act=search&keyword=xx&page=n&pagesize=20` |
| 书籍详情 | `mod=movie&act=detail&id=x` |
| 章节列表 | `mod=movie&act=movielist&id=x&page=n&pagesize=500` |
| 音频地址 | `act=wapseries&mod=movie&id=作品id&movieId=章节序号&t=时间戳&token=md5` |

两个坑：

1. **所有请求必须带 User-Agent**，否则 Tengine 直接 403。
2. 音频接口要签名：参数按 key 升序拼成
   `act=wapseries&id=..&mod=movie&movieId=..&t=..`，末尾追加
   `token=056a308c515e16b2fe5a5c631319339cbc60a8ee0e03d016` 后取 MD5。
   返回的 `data.SeriesUrl` 是 302 跳转，源码里手动跟随跳转拿到酷我真实 mp3 直链。

## 重新编译

源 Kotlin 代码基于 [eprendre/tingshu](https://github.com/eprendre/tingshu) 的 `CustomSources` 工程。

```bash
git clone https://github.com/eprendre/tingshu.git
cd tingshu/CustomSources

# 把 src/main/kotlin/com/github/eprendre/sources_by_m1ngzer/ 整个目录复制进去
cp -r <本仓库>/src/main/kotlin/com/github/eprendre/sources_by_m1ngzer \
      src/main/kotlin/com/github/eprendre/

# 指定打包目录
echo "MY_SOURCES_PACKAGE=sources_by_m1ngzer" > gradle.properties

# 打包（JDK 18+）
./gradlew jar          # Windows: gradlew.bat jar
```

产物在 `build/libs/sources_by_m1ngzer.jar`（仓库自带 `dx_win`，会自动转成 dex）。

## 更新 / 新增站点

1. 改代码或新增源类（一个站点一个类，加进 `SourceEntry.getSources()`）
2. **`external_sources.json` 里 `version` + 1**
3. 重新编译，替换仓库里的 jar
4. 提交推送 —— App 每次启动会自动检测更新，用户下拉刷新即可

## 自检

```bash
python tools/verify.py          # 默认测「花颜策」
python tools/verify.py 三体     # 指定关键词
```

依次验证：分类导航 → 分类列表 → 搜索 → 详情 → 章节 → 音频直链可播放。
