"""Chunk 文本匹配拼接 — 按文本后缀匹配去除相邻块间的重叠部分。

参考腾讯 WeKnora 的 AppendWithOverlap，实现纯函数文本拼接：
- 在 next 开头的窗口里搜索 acc 的后缀首次出现位置
- 找到则从该位置之后接上（跳过重叠部分）
- 找不到则原样拼接（不裁剪，宁保留不丢字）

不依赖位置坐标（chunk_index / offset），仅靠文本匹配，适用范围更广。
"""

MIN_OVERLAP_RUNES = 4  # 参与匹配的最短后缀长度（rune 数），中文 2 字 = 4 rune
DEFAULT_SEARCH_SPAN = 400  # 搜索窗口下限


def append_with_overlap(acc: str, next: str, position_overlap: int = 0) -> str:
    """把 next 追加到 acc 之后，去除二者间的重叠部分。

    按文本匹配（非位置坐标）检测重叠：
    - 在 next 开头的窗口里搜索 acc 的后缀首次出现位置
    - 找到则从该位置之后接上（跳过重叠部分）
    - 找不到则原样拼接（不裁剪，宁保留不丢字）

    position_overlap 仅用于估算搜索窗口大小，不用于裁剪。
    """
    if not acc:
        return next
    if not next:
        return acc

    acc_runes = list(acc)
    next_runes = list(next)

    span = max(position_overlap, 0)
    max_k = min(len(acc_runes), len(next_runes))
    cap = max(span * 3, DEFAULT_SEARCH_SPAN)
    if max_k > cap:
        max_k = cap
    head_slack = max(span * 2, 320)  # 允许跳过补写表头等合成文本

    for k in range(max_k, MIN_OVERLAP_RUNES - 1, -1):
        needle = acc_runes[len(acc_runes) - k:]
        pos = _index_runes(next_runes, needle, head_slack)
        if pos >= 0:
            return acc + "".join(next_runes[pos + k:])
    return acc + next


def merge_text_chunks(contents: list[str], gap_sep: str = "\n") -> str:
    """把多个文本块拼接为完整文本，去除相邻块间的重叠。"""
    if not contents:
        return ""
    merged = ""
    for content in contents:
        if not content:
            continue
        if not merged:
            merged = content
            continue
        # 位置信息不可用，用默认窗口
        merged = append_with_overlap(merged, content, 0)
    return merged


def _index_runes(haystack: list[str], needle: list[str], max_start: int) -> int:
    """在 haystack 中查找 needle 首次出现的下标，起始位置不超过 max_start。"""
    if not needle or len(needle) > len(haystack):
        return -1
    limit = min(len(haystack) - len(needle), max_start)
    for i in range(limit + 1):
        if haystack[i:i + len(needle)] == needle:
            return i
    return -1
