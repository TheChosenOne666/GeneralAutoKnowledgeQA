"""联网搜索服务（M4-3）。

基于 httpx + DuckDuckGo HTML 搜索，无需 API Key，返回结构化结果。
失败降级：网络超时/不可达时返回空列表，不阻断主流程。
"""

import re
import httpx
from html import unescape

from loguru import logger

from core.config import settings

WEB_SEARCH_TIMEOUT = 15.0
_DDG_LITE = "https://lite.duckduckgo.com/lite/"

# Duplicate removal: URL 去重前先归一化
_URL_CLEAN = re.compile(r"[?#].*$")

# Sniff results from DDG Lite HTML (simple format — stable structure)
_LINK_RE = re.compile(
    r'<a[^>]*href="([^"]+)"[^>]*>([^<]+)</a>', re.I
)
_SPAN_RE = re.compile(
    r'<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>\s*<span[^>]*>(.*?)</span>',
    re.S | re.I | re.DOTALL,
)


async def web_search(query: str, max_results: int = 5) -> list[dict]:
    """搜索网络并返回结果列表。

    Args:
        query: 搜索关键词
        max_results: 最多返回条数（默认 5）

    Returns:
        [{"title": str, "url": str, "snippet": str}, ...]
        失败时返回空列表。
    """
    if not query or not query.strip():
        return []

    try:
        async with httpx.AsyncClient(timeout=settings.web_search_timeout) as client:
            resp = await client.get(
                _DDG_LITE,
                params={"q": query.strip()},
                headers={
                    "User-Agent": (
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        "AppleWebKit/537.36"
                    ),
                    "Accept": "text/html",
                },
                follow_redirects=True,
            )
            resp.raise_for_status()
            results = _parse_ddg_lite(resp.text, max_results)
            logger.info(
                f"web_search: query={query!r} → {len(results)} results"
            )
            return results
    except (httpx.TimeoutException, httpx.HTTPError) as e:
        logger.warning(f"web_search 网络异常，返回空列表: {e}")
        return []
    except Exception as e:
        logger.exception(f"web_search 未知异常: {e}")
        return []


def _parse_ddg_lite(html: str, max_results: int) -> list[dict]:
    """解析 DuckDuckGo Lite 搜索结果 HTML。

    DDG Lite 结果结构（每行大致如下）：
        <a rel="nofollow" href="...">Title</a>
        <span class="link-text">... snippet ...</span>
    多行拼接后可能产生 <a>...</a><span>...</span> 相邻文本。

    先整页匹配 <a href=…>title</a><span>snippet</span> 对；若不够，再分别匹配。
    """
    seen_urls: set[str] = set()
    results: list[dict] = []

    # 策略 A：a+span 成对（标题 + 摘要）
    for m in _SPAN_RE.finditer(html):
        url = _normalize_url(m.group(1))
        if not url or url in seen_urls:
            continue
        title = unescape(_clean_html(m.group(2).strip()))
        snippet = unescape(_clean_html(m.group(3).strip()))
        if len(snippet) < 20:
            continue
        if not title:
            title = _title_from_url(url)
        seen_urls.add(url)
        results.append({"title": title, "url": url, "snippet": snippet})
        if len(results) >= max_results:
            return results

    # 策略 B：单独 <a> 标签
    for m in _LINK_RE.finditer(html):
        url = _normalize_url(m.group(1))
        if not url or url in seen_urls:
            continue
        title = unescape(_clean_html(m.group(2).strip()))
        if not title or len(title) < 3:
            continue
        seen_urls.add(url)
        results.append({"title": title, "url": url, "snippet": ""})
        if len(results) >= max_results:
            return results

    return results


def _normalize_url(url: str) -> str:
    """去参数/锚点，去重 + 过滤非 http 链接。"""
    url = url.strip()
    if not url.startswith(("http://", "https://")):
        return ""
    return _URL_CLEAN.sub("", url)


def _clean_html(text: str) -> str:
    """去除 HTML 标签，保留纯文本。"""
    return re.sub(r"<[^>]+>", "", text)


def _title_from_url(url: str) -> str:
    """从 URL 提取域名作为兜底标题。"""
    import urllib.parse
    parsed = urllib.parse.urlparse(url)
    host = parsed.netloc or parsed.path.split("/")[0]
    return host.replace("www.", "")


def format_search_results(results: list[dict]) -> str:
    """格式化搜索结果列表为 LLM 可读文本。"""
    if not results:
        return "未搜索到相关网络结果。"
    lines = []
    for i, r in enumerate(results, 1):
        title = r.get("title", "无标题")[:80]
        url = r.get("url", "")
        snippet = r.get("snippet", "（无摘要）")[:300]
        lines.append(f"[{i}] {title}\n    URL: {url}\n    {snippet}")
    return "\n\n".join(lines)
