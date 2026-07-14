"""联网搜索服务单测（M4-3）。

用桩替换 httpx.AsyncClient 避免真实网络请求，聚焦：
- HTML 解析正确性（DDG Lite 格式）
- 搜索结果数量裁剪
- 网络异常降级返回空列表
- 空查询直接返回空
- format_search_results 格式化
"""

import asyncio
import unittest

from services.web_search import (
    _parse_ddg_lite,
    _normalize_url,
    _clean_html,
    format_search_results,
    web_search,
)


# ── 模拟 DDG Lite HTML 响应 ──

_SAMPLE_HTML = """<!DOCTYPE html>
<html>
<body>
<div class="result">
<a rel="nofollow" href="https://example.com/page1">Example Title 1</a>
<span class="link-text">This is the first snippet with useful information.</span>
</div>
<div class="result">
<a rel="nofollow" href="https://example.com/page2?q=abc#section">Example Title 2</a>
<span class="link-text">Another result with <b>bold</b> text and more content here.</span>
</div>
<div class="result">
<a rel="nofollow" href="https://example.com/page3">Example Title 3</a>
<span class="link-text">Third result snippet.</span>
</div>
</body>
</html>"""


class WebSearchParseTest(unittest.TestCase):
    """DDG Lite HTML 解析。"""

    def test_parse_returns_results(self):
        results = _parse_ddg_lite(_SAMPLE_HTML, 5)
        self.assertEqual(len(results), 3)
        self.assertEqual(results[0]["title"], "Example Title 1")
        self.assertEqual(results[0]["url"], "https://example.com/page1")
        self.assertIn("first snippet", results[0]["snippet"])

    def test_parse_snippet_strips_html(self):
        results = _parse_ddg_lite(_SAMPLE_HTML, 5)
        snip = results[1]["snippet"]
        self.assertNotIn("<b>", snip)
        self.assertIn("bold", snip)

    def test_parse_clips_to_max(self):
        results = _parse_ddg_lite(_SAMPLE_HTML, 2)
        self.assertEqual(len(results), 2)

    def test_parse_empty_html(self):
        self.assertEqual(_parse_ddg_lite("", 5), [])
        self.assertEqual(_parse_ddg_lite("<html></html>", 5), [])

    def test_parse_no_valid_links(self):
        html = '<a href="ftp://bad.com">bad</a><span>snip</span>'
        self.assertEqual(_parse_ddg_lite(html, 5), [])

    def test_parse_ignores_duplicate_urls(self):
        html = (
            '<a href="https://x.com/a">A</a><span>s1 snippet text with enough length for test</span>'
            '<a href="https://x.com/a">A2</a><span>s2 snippet text with enough length for test</span>'
        )
        results = _parse_ddg_lite(html, 5)
        self.assertEqual(len(results), 1)

    def test_normalize_url_strips_params_and_fragment(self):
        self.assertEqual(
            _normalize_url("https://x.com/p?q=1#s"),
            "https://x.com/p",
        )

    def test_normalize_url_rejects_non_http(self):
        self.assertEqual(_normalize_url("ftp://x.com"), "")
        self.assertEqual(_normalize_url("javascript:void(0)"), "")

    def test_clean_html_strips_tags(self):
        self.assertEqual(_clean_html("<b>bold</b>"), "bold")
        self.assertEqual(_clean_html("<a href='x'>link</a> text"), "link text")


class WebSearchIntegrationTest(unittest.TestCase):
    """异步搜索集成（用桩替换 httpx 客户端）。"""

    class _FakeResponse:
        def __init__(self, text, status=200):
            self.text = text
            self.status_code = status

        def raise_for_status(self):
            if self.status_code >= 400:
                raise __import__("httpx").HTTPStatusError("error", request=None, response=self)

        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            pass

    class _FakeClient:
        def __init__(self, resp):
            self._resp = resp
            self._called_params = None

        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            pass

        async def get(self, url, params=None, headers=None, follow_redirects=True):
            self._called_params = params
            return WebSearchIntegrationTest._FakeResponse(self._resp)

    def test_search_with_results(self):
        fake_resp = _SAMPLE_HTML
        client_factory = lambda timeout: self._FakeClient(fake_resp)

        # patch httpx.AsyncClient
        import httpx
        original = httpx.AsyncClient
        try:
            httpx.AsyncClient = client_factory
            results = asyncio.run(web_search("test query", 3))
        finally:
            httpx.AsyncClient = original

        self.assertGreaterEqual(len(results), 2)
        self.assertIn("Example Title", results[0]["title"])
        self.assertIn("https://example.com", results[0]["url"])

    def test_search_network_error_returns_empty(self):
        import httpx
        import services.web_search as ws

        original = httpx.AsyncClient
        try:
            def _raise(*args, **kwargs):
                raise httpx.TimeoutException("timeout")

            httpx.AsyncClient = _raise
            results = asyncio.run(web_search("test", 5))
        finally:
            httpx.AsyncClient = original

        self.assertEqual(results, [])

    def test_search_empty_query(self):
        self.assertEqual(asyncio.run(web_search("", 5)), [])
        self.assertEqual(asyncio.run(web_search("   ", 5)), [])


class FormatResultsTest(unittest.TestCase):
    """结果格式化。"""

    def test_format_with_results(self):
        results = [
            {"title": "Test", "url": "https://x.com", "snippet": "A snippet"},
        ]
        formatted = format_search_results(results)
        self.assertIn("[1] Test", formatted)
        self.assertIn("URL: https://x.com", formatted)
        self.assertIn("A snippet", formatted)

    def test_format_empty(self):
        self.assertIn("未搜索到", format_search_results([]))


if __name__ == "__main__":
    unittest.main()
