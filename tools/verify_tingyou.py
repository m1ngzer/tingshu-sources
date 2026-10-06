# -*- coding: utf-8 -*-
"""
听友听书 (www.tingyou.fm) 听书源 全链路自检脚本
用法:
    python tools/verify_tingyou.py              # 默认用 "盗墓笔记" 测试
    python tools/verify_tingyou.py 三体         # 指定关键词
全部通过说明源没挂；哪一步报错就是哪一步需要改。
注意：本机直连 tingyou.fm 不通（连接被重置），必须走系统代理 127.0.0.1:7897，
脚本已内置该代理；代理关了就会全部失败。
"""
import json
import sys
import urllib.parse
import urllib.request
import uuid

UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
BASE = "https://www.tingyou.fm"
API = BASE + "/listening-api"
PROXY = "http://127.0.0.1:7897"
SESSION = str(uuid.uuid4())

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

_opener = urllib.request.build_opener(
    urllib.request.ProxyHandler({"http": PROXY, "https": PROXY})
)


def _headers(extra=None):
    h = {
        "User-Agent": UA,
        "Referer": BASE + "/listening",
        "Accept": "application/json",
        "x-listening-session": SESSION,
        "x-listening-timezone": "Asia/Shanghai",
    }
    if extra:
        h.update(extra)
    return h


def get(path):
    req = urllib.request.Request(API + "/" + path, headers=_headers())
    with _opener.open(req, timeout=25) as r:
        return json.loads(r.read().decode("utf-8", "replace"))


def post(path, body):
    req = urllib.request.Request(
        API + "/" + path,
        data=json.dumps(body).encode("utf-8"),
        headers=_headers({"Content-Type": "application/json"}),
        method="POST",
    )
    with _opener.open(req, timeout=25) as r:
        return json.loads(r.read().decode("utf-8", "replace"))


def ok(msg):
    print("[OK]", msg)


def fail(msg):
    print("[FAIL]", msg)
    sys.exit(1)


def main():
    keyword = sys.argv[1] if len(sys.argv) > 1 else "盗墓笔记"

    # 1) 分类
    f = get("filters")
    cats = f.get("categories") or []
    sorts = f.get("sorts") or []
    if not cats:
        fail("filters 接口没有返回 categories")
    ok("分类导航：%d 个大类，%d 种排序（%s）" % (
        len(cats), len(sorts), "、".join(c.get("name", "?") for c in cats[:6])))

    # 2) 分类列表
    first_type = None
    for c in cats:
        types = c.get("types") or []
        if types:
            first_type = (c, types[0])
            break
    if not first_type:
        fail("filters 里没有任何子分类")
    cat, typ = first_type
    q = urllib.parse.urlencode({
        "category": cat["id"], "type": typ["id"], "sort": "popular", "page": 1})
    cl = get("category?" + q)
    items = cl.get("data") or cl.get("items") or []
    if not items:
        fail("分类列表为空：%s/%s" % (cat.get("name"), typ.get("name")))
    ok("分类列表：%s/%s 共 %s 页，首条《%s》" % (
        cat.get("name"), typ.get("name"), cl.get("pages"), items[0].get("title")))

    # 3) 搜索
    s = post("search", {"keyword": keyword, "page": 1})
    results = s.get("results") or s.get("items") or []
    if not results:
        fail("搜索 «%s» 无结果" % keyword)
    ok("搜索 «%s»：%d 条，has_more=%s，首条《%s》" % (
        keyword, len(results), s.get("has_more"), results[0].get("title")))

    # 4) 详情（用搜索结果第一条）
    aid = str(results[0].get("id") or results[0].get("album_id"))
    a = get("album/" + aid)
    title = a.get("title")
    if not title:
        fail("专辑详情为空 id=%s" % aid)
    ok("详情：《%s》 作者:%s 播音:%s 章节数:%s 状态:%s" % (
        title, a.get("author"), a.get("teller"), a.get("count"),
        "连载" if a.get("status") == 1 else "完结"))

    # 5) 章节
    ch = get("chapters/" + aid)
    chapters = ch.get("chapters") or []
    if not chapters:
        fail("章节列表为空 id=%s" % aid)
    idx = chapters[0].get("index")
    ok("章节：共 %d 集，首集 idx=%s《%s》" % (
        len(chapters), idx, chapters[0].get("title")))

    # 6) 音频
    p = post("play", {"album_id": aid, "chapter_idx": idx})
    play_url = p.get("play_url") or ""
    if not play_url.startswith("http"):
        fail("播放地址无效：%r" % play_url)
    req = urllib.request.Request(play_url, headers={"User-Agent": UA, "Range": "bytes=0-1023"})
    try:
        with _opener.open(req, timeout=25) as r:
            head = r.read(16)
            code = r.status
            ctype = r.headers.get("Content-Type", "")
    except urllib.error.HTTPError as e:
        code, ctype, head = e.code, "", b""
    if code not in (200, 206):
        fail("音频请求失败 HTTP %s：%s" % (code, play_url[:80]))
    ok("音频：HTTP %s %s，首 4 字节 %r" % (code, ctype, head[:4]))

    print("\n全部通过 ✅")


if __name__ == "__main__":
    main()
