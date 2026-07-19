"""chunk_merge 单测 — M6-2 文本匹配拼接。"""

import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from services.chunk_merge import append_with_overlap, merge_text_chunks


def test_no_overlap():
    """无重叠 → 原样拼接"""
    result = append_with_overlap("hello world", "foo bar")
    assert result == "hello worldfoo bar", f"Expected exact concat, got: {result!r}"


def test_exact_overlap():
    """尾头完全重叠 → 去重"""
    result = append_with_overlap("hello world", "world and more")
    assert result == "hello world and more", f"Expected dedup, got: {result!r}"


def test_partial_overlap():
    """部分重叠 → 去重叠部分"""
    result = append_with_overlap("这是第一段内容的后半部分", "后半部分内容继续延伸")
    assert "后半部分" in result
    # 去重后不应出现两次"后半部分"
    assert result.count("后半部分") == 1, f"Expected 1 overlap, got: {result.count('后半部分')}"


def test_table_header():
    """含表头前缀的重叠 → 跳过表头后匹配到重叠"""
    acc = "| 列1 | 列2 |\n|-----|-----|\n数据行1"
    next_text = "| 列1 | 列2 |\n|-----|-----|\n数据行1 续行"
    result = append_with_overlap(acc, next_text)
    # acc 后缀 "数据行1" 应在 next 的表头之后被匹配到，去重后 "数据行1" 只出现一次
    assert result.count("数据行1") == 1, f"Overlap not detected after table header: {result!r}"


def test_html_entity():
    """HTML 实体 → 不受字符数偏差影响"""
    acc = "文本包含 &#34;引号&#34; 内容"
    next_text = "&#34;引号&#34; 内容继续"
    result = append_with_overlap(acc, next_text)
    # 应该能匹配到重叠部分并去重
    assert result.count("&#34;引号&#34;") == 1, f"HTML entity duplicated: {result!r}"


def test_empty():
    """空块 → 跳过"""
    assert merge_text_chunks([]) == ""
    assert merge_text_chunks(["", "abc"]) == "abc"
    assert merge_text_chunks(["abc", ""]) == "abc"


def test_single_chunk():
    """单块 → 不拼接"""
    result = merge_text_chunks(["only one chunk"])
    assert result == "only one chunk"


def test_non_adjacent():
    """多块不相邻（无重叠）→ 原样拼接"""
    chunks = ["第一段内容", "第二段完全不同", "第三段也是独立"]
    result = merge_text_chunks(chunks)
    assert "第一段内容" in result
    assert "第二段完全不同" in result
    assert "第三段也是独立" in result


if __name__ == "__main__":
    tests = [
        test_no_overlap,
        test_exact_overlap,
        test_partial_overlap,
        test_table_header,
        test_html_entity,
        test_empty,
        test_single_chunk,
        test_non_adjacent,
    ]
    passed = 0
    for t in tests:
        try:
            t()
            print(f"  [PASS] {t.__name__}")
            passed += 1
        except AssertionError as e:
            print(f"  [FAIL] {t.__name__}: {e}")
        except Exception as e:
            print(f"  [FAIL] {t.__name__}: {e!r}")
    print(f"\n{'='*40}\nchunk_merge: {passed}/{len(tests)} passed")
