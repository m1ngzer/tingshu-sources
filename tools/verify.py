# -*- coding: utf-8 -*-
"""
书音FM (m.mekui.com) 听书源 全链路自检脚本
用法:
    python tools/verify.py              # 默认用 "花颜策" 测试
    python tools/verify.py 三体         # 指定关键词
全部通过说明源没挂；哪一步报错就是哪一步需要改。
"""
import hashlib
import json
import sys
import time
import urllib.parse
import urllib.request

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
BASE = "https://m.mekui.com"
API = BASE + "/ecmsapi/index.php"
TOKEN_KEY = "056a308c515e16b2fe5a5c631319339cbc60a8ee0e03d016"

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass


def get(params):
    url = API + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url)
    req.add_header("User-Agent", UA)
    req.add_header("Referer", BASE + "/")
    req.add_header("Accept", "application/json, text/plain, */*")
    with urllib.request.urlopen(req, timeout=25) as r:
        return json.loads(r.read().decode("utf-8", "replace"))


def md5(s):
    return hashlib.md5(s.encode("utf-8")).hexdigest()


def resolve_redirect(start, max_hop=3):
    current = start
    for _ in range(max_hop):
        req = urllib.request.Request(current)
        req.add_header("User-Agent", UA)
        req.add_header("Referer", BASE + "/")
        opener = urllib.request.build_opener(NoRedirect)
        try:
            resp = opener.open(req, timeout=20)
            return current, resp.status
        except urllib.error.HTTPError as e:
            if e.code in (301, 302, 303, 307, 308):
                loc = e.headers.get("Location")
                if not loc:
                    return current, e.code
                current = urllib.parse.urljoin(current, loc)
            else:
                return current, e.code
    return current, None


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def ok(msg):
    print("  [OK]   " + msg)


def fail(msg):
    print("  [FAIL] " + msg)
    return False


def main():
    keyword = sys.argv[1] if len(sys.argv) > 1 else "花颜策"
    all_ok = True

    print("1) 分类导航")
    nav = get({"mod": "column", "act": "navigation", "classid": "0"})
    tops = nav.get("list") or []
    if tops:
        ok("一级分类 %d 个: %s" % (len(tops), "/".join(c["classname"] for c in tops[:5]) + "..."))
    else:
        all_ok = fail("分类导航为空")

    print("2) 分类列表 (有声小说-言情 classid=1)")
    d = get({"mod": "movie", "act": "list", "classid": "1", "page": "1", "pagesize": "5"}).get("data", {})
    if (d.get("list") or []):
        ok("共 %s 本，首页 %d 条，第一本《%s》" % (d.get("total"), len(d["list"]), d["list"][0]["title"]))
    else:
        all_ok = fail("分类列表为空")

    print("3) 搜索：%s" % keyword)
    d = get({"mod": "movie", "act": "search", "keyword": keyword, "page": "1", "pagesize": "5"}).get("data", {})
    results = d.get("list") or []
    if results:
        ok("命中 %s 条，第一条《%s》id=%s" % (d.get("total"), results[0]["title"], results[0]["id"]))
    else:
        all_ok = fail("搜索无结果")
        return 1

    book_id = str(results[0]["id"])

    print("4) 书籍详情 id=%s" % book_id)
    detail = get({"mod": "movie", "act": "detail", "id": book_id}).get("data", {}).get("detail") or {}
    if detail.get("title"):
        ok("标题《%s》 播音=%s 封面=%s" % (
            detail.get("title"), detail.get("player", "")[:20], (detail.get("titlepic") or "")[:50]))
    else:
        all_ok = fail("详情为空")

    print("5) 章节列表")
    d = get({"mod": "movie", "act": "movielist", "id": book_id, "page": "1", "pagesize": "500"}).get("data", {})
    chapters = d.get("moielist") or []
    if chapters:
        ok("共 %s 章，本次取回 %d 章，第一章《%s》" % (d.get("count"), len(chapters), chapters[0]["title"]))
    else:
        all_ok = fail("章节列表为空")
        return 1

    print("6) 音频直链 (第 1 章)")
    ts = int(time.time())
    raw = "act=wapseries&id=%s&mod=movie&movieId=%s&t=%s" % (book_id, chapters[0]["id"], ts)
    token = md5(raw + "&token=" + TOKEN_KEY)
    root = json.loads(urllib.request.urlopen(
        urllib.request.Request(API + "?" + raw + "&token=" + token,
                               headers={"User-Agent": UA, "Referer": BASE + "/"}),
        timeout=25).read().decode("utf-8", "replace"))
    data = root.get("data") or {}
    audio = data.get("SeriesUrl") or ""
    if not audio:
        all_ok = fail("wapseries 未返回 SeriesUrl")
        return 1
    ok("SeriesUrl = %s" % audio[:90])

    real, code = resolve_redirect(audio)
    if real != audio:
        ok("跳转解析 -> %s" % real[:90])
    req = urllib.request.Request(real)
    req.add_header("User-Agent", UA)
    req.add_header("Range", "bytes=0-1024")
    try:
        with urllib.request.urlopen(req, timeout=25) as resp:
            ct = resp.headers.get("Content-Type") or ""
            body = resp.read()
            if "audio" in ct or "mpeg" in ct or body[:3] == b"ID3":
                ok("音频可播放：HTTP %s, %s" % (resp.status, ct))
            else:
                all_ok = fail("返回的不是音频：%s" % ct)
    except Exception as e:
        all_ok = fail("音频请求失败：%s" % e)

    print()
    print("全部通过 ✅" if all_ok else "存在问题 ❌，按上面 FAIL 行定位")
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
