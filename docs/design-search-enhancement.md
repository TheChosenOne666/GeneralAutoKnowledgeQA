# 检索增强方案设计文档

> 参考腾讯 WeKnora 混合检索系统，移植 4 项能力到熊答 AI  
> 创建时间：2026-07-19  
> 状态：方案设计

---

## 1. 背景与目标

### 1.1 现状

熊答 AI 当前的检索流程（`ai-service/services/rag.py`）：

```
用户查询
  → Query 向量化（只算一次）
  → 向量检索 Top-20（pgvector）
  → BM25 关键词检索 Top-20（ES 7.17）
  → 向量主导融合（语义优先，BM25 仅补充不足，平手时词法重合度决胜）
  → 排除增强块（qa/summary/wiki/entity 等不作为引用来源）
  → LLM/Rerank 精排（候选池 10 → 精排取 top_n）
  → 相关性门槛过滤（rerank ≥ 0.40，向量 ≥ 0.30）
  → 父子分块回溯（small-to-big）
  → L1 Redis 缓存（1h TTL）
```

### 1.2 与 WeKnora 的差距

| 维度 | WeKnora | 熊答 AI | 差距 |
|------|---------|---------|------|
| RRF 参数 | 租户级可配（k/权重存 DB） | 硬编码在 config.py | 用户无法按数据特征调参 |
| Chunk 拼接 | 文本匹配去重叠（不依赖位置坐标） | 无拼接逻辑 | 未来精细分块会错位/丢字 |
| 相邻块补全 | 检索命中后补全前一块+后一块上下文 | 仅回溯父块（small-to-big） | 缺少同文档前后块上下文 |
| FAQ 负向过滤 | 负向问题匹配排除 | 无此机制 | FAQ 场景可能命中"看似相关但答非所问"的条目 |

### 1.3 目标

移植以下 4 项能力，编号 M6-1 ~ M6-4：

| 编号 | 功能 | 优先级 | 投入 | 预期收益 |
|------|------|--------|------|----------|
| M6-1 | RRF 参数可配置化 | P0 | 低 | 不同数据特征可调最优权重 |
| M6-2 | Chunk 文本匹配拼接 | P0 | 低 | 为精细分块策略打基础，避免拼接错位 |
| M6-3 | FAQ 负向问题过滤 | P1 | 中低 | FAQ 场景精准排除"看似相关"的条目 |
| M6-4 | 相邻块补全 | P1 | 中低 | 检索命中后补全前后块上下文，LLM 获得更完整语境 |

---

## 2. M6-1：RRF 参数可配置化

### 2.1 设计思路

当前 `config.py` 中的融合参数是全局环境变量（`.env`），无法按租户/知识库区分。参考 WeKnora 的 `RetrievalConfig`，把融合参数提升为**租户级配置**，存数据库，有默认值兜底。

### 2.2 数据模型

在 Java 后端的 `tenant` 表新增 JSONB 字段 `retrieval_config`：

```sql
ALTER TABLE tenant ADD COLUMN IF NOT EXISTS retrieval_config JSONB;

-- 默认值
UPDATE tenant SET retrieval_config = '{
  "rrf_k": 60,
  "rrf_vector_weight": 0.7,
  "rrf_keyword_weight": 0.3,
  "vector_min_relevance": 0.30,
  "bm25_min_relevance": 1.0,
  "rerank_min_relevance": 0.40,
  "relative_ratio": 0.80,
  "max_chunks_per_doc": 5
}'::jsonb WHERE retrieval_config IS NULL;
```

字段说明：

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `rrf_k` | int | 60 | RRF 平滑常数 |
| `rrf_vector_weight` | float | 0.7 | 向量权重 |
| `rrf_keyword_weight` | float | 0.3 | 关键词权重 |
| `vector_min_relevance` | float | 0.30 | 向量余弦相似度门槛 |
| `bm25_min_relevance` | float | 1.0 | BM25 分数门槛 |
| `rerank_min_relevance` | float | 0.40 | Rerank 相关性分数门槛 |
| `relative_ratio` | float | 0.80 | 相对相关性阈值 |
| `max_chunks_per_doc` | int | 5 | 单文档最多进入 top-N 的块数 |

### 2.3 后端接口

**Java 端**：

- `GET /api/tenant/retrieval-config` — 获取当前租户的检索配置
- `PUT /api/tenant/retrieval-config` — 更新检索配置

```java
// TenantController.java
@GetMapping("/retrieval-config")
public BaseResponse<String> getRetrievalConfig() {
    // 从 tenant 表读取 retrieval_config JSONB，返回 JSON 字符串
}

@PutMapping("/retrieval-config")
public BaseResponse<Boolean> updateRetrievalConfig(@RequestBody String configJson) {
    // 校验 JSON 合法性 + 字段范围，写入 tenant.retrieval_config
}
```

**Python AI 服务**：

Java → Python 透传检索配置。两种方案：

**方案 A（推荐）**：Java 在调用 Python 的 `/ai/chat/stream` 时，在 request body 中带上 `retrieval_config` 字段。Python 端读出来覆盖 `settings` 的默认值。

```python
# 修改 ai_service/api/routes.py 的 chat 请求模型
class ChatRequest(BaseModel):
    # ... 现有字段 ...
    retrieval_config: dict | None = None  # 租户级检索配置覆盖
```

```python
# 修改 rag.py 的 retrieve 方法签名
async def retrieve(
    self, query, kb_ids, tenant_id, top_n=5, cfg=None,
    enhance=False, retrieval_config: dict | None = None,
) -> list[RetrievalResult]:
    # 用 retrieval_config 覆盖 settings 默认值
    rrf_k = (retrieval_config or {}).get("rrf_k", settings.retrieval_rerank_top_k)
    # ...
```

**方案 B**：Python 直接查 Java 后端的 `/api/tenant/retrieval-config`。耦合更高，不推荐。

### 2.4 前端

在 `AIConfigPage.tsx` 新增「检索配置」卡片：

```
┌─ 检索配置 ─────────────────────────────┐
│                                         │
│  RRF 平滑常数 K:  [  60  ]              │
│  向量权重:        [ 0.7  ]              │
│  关键词权重:      [ 0.3  ]              │
│  向量相似度门槛:  [ 0.30 ]              │
│  BM25 门槛:       [ 1.0  ]              │
│  Rerank 门槛:     [ 0.40 ]              │
│  相对相关性阈值:  [ 0.80 ]              │
│  单文档最大块数:  [  5   ]              │
│                                         │
│  [ 恢复默认 ]    [ 保存 ]               │
│                                         │
└─────────────────────────────────────────┘
```

### 2.5 影响范围

| 文件 | 改动 |
|------|------|
| `backend/schema.sql` | tenant 表加 `retrieval_config` JSONB 列 |
| `backend/.../TenantController.java` | 新增 GET/PUT 接口 |
| `backend/.../AiServiceClient.java` | chat 请求加 `retrievalConfig` 字段 |
| `frontend/.../AIConfigPage.tsx` | 新增检索配置卡片 |
| `frontend/.../types/index.ts` | 新增 `RetrievalConfig` 类型 |
| `ai-service/api/routes.py` | ChatRequest 加 `retrieval_config` 字段 |
| `ai-service/services/rag.py` | `retrieve()` 读 `retrieval_config` 覆盖默认值 |
| `ai-service/core/config.py` | 保留 `.env` 默认值作为 fallback |

### 2.6 兼容性

- `retrieval_config` 为 NULL 时，Python 端用 `settings` 默认值，行为与当前完全一致
- 前端「恢复默认」按钮把字段置空，后端存 NULL
- 不影响 L1 缓存 key（缓存 key 已纳入 rerank 方式和 top_k，可进一步纳入 rrf_k）

---

## 3. M6-2：Chunk 文本匹配拼接

### 3.1 设计思路

当前父子分块策略中，检索命中子块后回溯父块内容（`attach_parents`），但**没有处理子块之间的重叠拼接**。当多个相邻子块被命中时，LLM 上下文中会出现内容重复或断裂。

参考 WeKnora 的 `AppendWithOverlap`，实现一个**纯函数**，按文本后缀匹配去除重叠，不依赖位置坐标。

### 3.2 算法设计

```python
# ai-service/services/chunk_merge.py（新文件）

MIN_OVERLAP_RUNES = 12   # 参与匹配的最短后缀长度
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
```

### 3.3 集成点

**集成到 `attach_parents` 之后**：

当前 `attach_parents` 只填充 `parent_content` 字段。新增逻辑：当多个命中块属于同一文档且 chunk_index 相邻时，用 `merge_text_chunks` 把它们的 `content` 拼接成更完整的上下文。

```python
# rag.py 的 retrieve 方法中，attach_parents 之后新增
if settings.retrieval_parent_context and merged:
    try:
        merged = await vector_store_module.vector_store_service.attach_parents(merged)
        # M6-2：相邻块重叠拼接
        merged = self._merge_adjacent_chunks(merged)
    except Exception as e:
        logger.warning(f"[父子分块/相邻拼接] 失败，降级用原始块: {e}")
```

```python
# rag.py 新增方法
def _merge_adjacent_chunks(self, results: list[RetrievalResult]) -> list[RetrievalResult]:
    """同一文档中 chunk_index 相邻的命中块，用文本匹配拼接去除重叠。"""
    from services.chunk_merge import merge_text_chunks

    # 按 doc_id 分组，每组内按 chunk_index 排序
    by_doc: dict[str, list[RetrievalResult]] = {}
    for r in results:
        by_doc.setdefault(r.doc_id, []).append(r)

    merged_results: list[RetrievalResult] = []
    for doc_id, group in by_doc.items():
        group.sort(key=lambda r: r.chunk_index)
        if len(group) <= 1:
            merged_results.extend(group)
            continue

        # 检测相邻性：chunk_index 连续或差 1
        is_adjacent = all(
            group[i + 1].chunk_index - group[i].chunk_index <= 1
            for i in range(len(group) - 1)
        )
        if not is_adjacent:
            merged_results.extend(group)
            continue

        # 拼接相邻块内容
        contents = [r.content for r in group]
        merged_content = merge_text_chunks(contents, gap_sep="\n")
        # 保留第一个块的元信息，content 替换为拼接后的
        first = group[0]
        first.content = merged_content
        merged_results.append(first)

    return merged_results
```

### 3.4 影响范围

| 文件 | 改动 |
|------|------|
| `ai-service/services/chunk_merge.py` | **新建**，纯函数模块 |
| `ai-service/services/rag.py` | `retrieve()` 中 `attach_parents` 后新增 `_merge_adjacent_chunks` 调用 |
| `ai-service/tests/test_chunk_merge.py` | **新建**，单测 |

### 3.5 边界情况

| 情况 | 处理 |
|------|------|
| 单个命中块 | 不拼接，原样返回 |
| 多块但不相邻 | 不拼接，原样返回 |
| 多块相邻但无重叠 | `append_with_overlap` 找不到后缀匹配 → 原样拼接（不丢字） |
| 补写表头（零宽度） | `head_slack` 允许跳过前缀，从表头后开始匹配 |
| HTML 实体（`&#34;`） | 文本匹配不受字符数偏差影响 |
| 空内容块 | 跳过 |

### 3.6 单测计划

```python
# test_chunk_merge.py
def test_no_overlap():           # 无重叠 → 原样拼接
def test_exact_overlap():        # 尾头完全重叠 → 去重
def test_partial_overlap():      # 部分重叠 → 去重叠部分
def test_table_header():         # 补写表头 → 跳过前缀匹配
def test_html_entity():          # HTML 实体 → 不受字符数偏差影响
def test_empty():                # 空块 → 跳过
def test_single_chunk():         # 单块 → 不拼接
def test_non_adjacent():         # 不相邻 → 不拼接
```

---

## 4. M6-3：FAQ 负向问题过滤

### 4.1 设计思路

参考 WeKnora 的 `filterByNegativeQuestions`，在 FAQ 类型的 chunk 上新增 `negative_questions` 元数据字段。检索后做一轮过滤：用户 query 完全匹配某负向问题 → 该 chunk 被剔除。

### 4.2 数据模型

当前 chunk 的 `metadata` JSONB 字段已支持 `chunk_type`、`parent_id` 等扩展字段。新增 `negative_questions`：

```json
// metadata
{
  "chunk_type": "qa",
  "chunk_index": 5,
  "negative_questions": ["什么是前端开发规范", "如何部署前端项目"]
}
```

**不需要改表结构**，`metadata` JSONB 已有。只需在分块/增强阶段支持写入。

### 4.3 写入入口

在 M5-7 的 QA 增强流程中（`document_processor.py` 的 `generate_qa_pairs` / `generate_enhanced_content`），生成 QA 对时，让 LLM 同时生成负向问题：

```python
# document_processor.py，QA 生成 prompt 调整
QA_WITH_NEGATIVE_PROMPT = """
基于以下文本，生成 3-5 个问答对。
对于每个问题，额外生成 1-2 个"看似相关但实际不应命中此答案"的负向问题。

输出 JSON 数组：
[
  {
    "question": "什么是后端开发规范？",
    "answer": "后端开发规范包括...",
    "negative_questions": ["什么是前端开发规范？", "如何部署前端项目？"]
  }
]
"""
```

写入 metadata：

```python
# document_processor.py
meta = {
    "chunk_index": state["index"],
    "chunk_type": "qa",
    "negative_questions": qa_item.get("negative_questions", []),
}
```

### 4.4 检索过滤

在 `rag.py` 的 `retrieve` 方法中，rerank 之后、父子分块回溯之前，新增负向问题过滤：

```python
# rag.py，rerank 之后
if settings.enable_negative_question_filter:
    merged = self._filter_negative_questions(query, merged)
```

```python
# rag.py 新增方法
@staticmethod
def _filter_negative_questions(
    query: str, results: list[RetrievalResult]
) -> list[RetrievalResult]:
    """FAQ 负向问题过滤：用户 query 完全匹配某负向问题 → 剔除该 chunk。"""
    if not results or not query:
        return results

    query_lower = query.strip().lower()
    if not query_lower:
        return results

    filtered = []
    for r in results:
        # 从 metadata 读取 negative_questions（仅 QA 类型 chunk 有）
        # 注意：RetrievalResult 当前不携带完整 metadata，需要额外查询
        # 简化方案：仅当 r.chunk_type == "qa" 且有 negative_questions 时过滤
        neg_qs = r.negative_questions  # 需要在 RetrievalResult 中新增字段
        if not neg_qs:
            filtered.append(r)
            continue
        matched = any(query_lower == nq.strip().lower() for nq in neg_qs)
        if not matched:
            filtered.append(r)
        else:
            logger.info(f"[负向过滤] 命中负向问题，剔除 chunk: {r.doc_id}:{r.chunk_index}")

    return filtered
```

**RetrievalResult 扩展**：

```python
@dataclass
class RetrievalResult:
    # ... 现有字段 ...
    negative_questions: list[str] = field(default_factory=list)
```

在 `vector_store.py` 的检索结果构造中，从 metadata 解析 `negative_questions`：

```python
# vector_store.py
RetrievalResult(
    # ... 现有字段 ...
    negative_questions=r.metadata.get("negative_questions", []),
)
```

### 4.5 过滤策略

与 WeKnora 一致，采用**精确匹配**（非模糊匹配）：

- 用户 query 完全等于负向问题 → 剔除
- 部分包含不剔除（避免误伤）

原因：负向问题过滤的目的是排除"用户问的问题字面与 FAQ 的负向问题完全一致"的场景，精确匹配最安全。

### 4.6 影响范围

| 文件 | 改动 |
|------|------|
| `ai-service/services/rag.py` | 新增 `_filter_negative_questions` 方法和调用 |
| `ai-service/services/vector_store.py` | `RetrievalResult` 加 `negative_questions` 字段，检索时从 metadata 解析 |
| `ai-service/services/document_processor.py` | QA 生成 prompt 调整，支持生成负向问题，写入 metadata |
| `ai-service/services/augment_queue.py` | 增强 worker 处理时传递 negative_questions |
| `ai-service/core/config.py` | 新增 `enable_negative_question_filter: bool = True` |
| `ai-service/tests/test_negative_filter.py` | **新建**，单测 |

### 4.7 兼容性

- 旧 chunk 的 metadata 没有 `negative_questions` 字段 → `get("negative_questions", [])` 返回空列表 → 不过滤
- `enable_negative_question_filter = False` 可全局关闭
- 不影响现有 L1 缓存 key（纳入 `enable_negative_question_filter` 开关即可）

---

## 5. M6-4：迭代检索扩召回

### 5.1 设计思路

参考 WeKnora 的 `iterativeRetrieveWithDeduplication`，当首次检索召回不足时（结果数 < 目标 top_n），翻倍 TopK 重试，最多 N 轮，每轮缓存 chunk 数据避免重复 DB 查询。

与 WeKnora 的差异：
- WeKnora 是多分组 fan-out 架构，每轮翻倍各分组的 TopK
- 我们是单库检索，每轮翻倍向量检索和 BM25 检索的 TopK

### 5.2 算法设计

```python
# rag.py 新增方法

async def _retrieve_with_iteration(
    self,
    query: str,
    kb_ids: list[str],
    tenant_id: str,
    target_count: int,
    cfg: ModelConfig | None,
    max_iterations: int = 3,
) -> tuple[list[RetrievalResult], list[RetrievalResult], float, float]:
    """迭代检索：召回不足时翻倍 TopK 重试。

    每轮：向量检索 + BM25 检索，去重合并，检查是否达到 target_count。
    TopK 翻倍策略：初始 20 → 40 → 80 → 160（上限 200）。
    """
    top_k = 20
    unique_vec: dict[tuple, RetrievalResult] = {}
    unique_bm25: dict[tuple, RetrievalResult] = {}
    best_vec = 0.0
    best_bm25 = 0.0

    for i in range(max_iterations):
        query_vec = await embedding_service.embed_text(query, cfg)

        vec_results = await vector_store_module.vector_store_service.search(
            query_vec, kb_ids, tenant_id, top_k=top_k
        )
        bm25_results = await vector_store_module.vector_store_service.keyword_search(
            query, kb_ids, tenant_id, top_k=top_k
        )

        # 去重合并（保留最高分）
        for r in vec_results:
            key = (r.doc_id, r.kb_id, r.chunk_index)
            if key not in unique_vec or r.score > unique_vec[key].score:
                unique_vec[key] = r
        for r in bm25_results:
            key = (r.doc_id, r.kb_id, r.chunk_index)
            if key not in unique_bm25 or r.score > unique_bm25[key].score:
                unique_bm25[key] = r

        best_vec = max(best_vec, (vec_results[0].score if vec_results else 0.0))
        best_bm25 = max(best_bm25, (bm25_results[0].score if bm25_results else 0.0))

        total_unique = len(unique_vec) + len(unique_bm25)
        logger.info(
            f"[迭代检索] 轮次 {i+1}/{max_iterations} top_k={top_k} "
            f"vec_unique={len(unique_vec)} bm25_unique={len(unique_bm25)} "
            f"target={target_count}"
        )

        # 召回足够或 TopK 已达上限或无新增结果 → 停止
        if total_unique >= target_count:
            break
        if top_k >= 200:
            break
        # 本轮无新增 → 引擎已无更多数据 → 停止
        if i > 0 and not vec_results and not bm25_results:
            break

        top_k = min(top_k * 2, 200)

    return (
        list(unique_vec.values()),
        list(unique_bm25.values()),
        best_vec,
        best_bm25,
    )
```

### 5.3 集成点

在 `rag.py` 的 `retrieve` 方法中，用 `_retrieve_with_iteration` 替换 `_search_core`：

```python
# rag.py 的 retrieve 方法中
# 替换：
# vec_results, bm25_results, best_vec, best_bm25 = await self._search_core(
#     search_query, kb_ids, tenant_id, cfg
# )
# 为：
if settings.enable_iterative_retrieval:
    vec_results, bm25_results, best_vec, best_bm25 = await self._retrieve_with_iteration(
        search_query, kb_ids, tenant_id,
        target_count=settings.retrieval_rerank_top_k if will_rerank else top_n,
        cfg=cfg,
    )
else:
    vec_results, bm25_results, best_vec, best_bm25 = await self._search_core(
        search_query, kb_ids, tenant_id, cfg
    )
```

### 5.4 配置

```python
# config.py 新增
enable_iterative_retrieval: bool = True       # 迭代检索扩召回总开关
iterative_retrieval_max_rounds: int = 3        # 最大迭代轮次
iterative_retrieval_initial_topk: int = 20    # 初始 TopK
iterative_retrieval_max_topk: int = 200        # TopK 上限
```

### 5.5 与现有 query expansion 的关系

当前已有 `enable_query_expansion`：主检索召回不足时用 LLM 生成扩展 query 再检索。两者**互补不冲突**：

| 机制 | 触发条件 | 做什么 |
|------|----------|--------|
| 迭代检索（M6-4） | 召回 < target_count | 同一 query 翻倍 TopK 重试 |
| query expansion | 召回 < `retrieval_expansion_min`（默认 3） | LLM 生成新 query 再检索 |

执行顺序：先迭代检索（扩大 TopK），如果仍不足再 query expansion（换 query）。迭代检索是"同 query 更深挖"，expansion 是"换角度问"。

### 5.6 影响

| 文件 | 改动 |
|------|------|
| `ai-service/services/rag.py` | 新增 `_retrieve_with_iteration` 方法，`retrieve` 中条件调用 |
| `ai-service/core/config.py` | 新增 4 个迭代检索配置项 |
| `ai-service/tests/test_iterative_retrieval.py` | **新建**，单测 |

### 5.7 性能考量

- 每轮迭代需要 1 次向量检索 + 1 次 BM25 检索（不重新算 embedding，复用第一次的 query_vec）
- 最坏情况 3 轮：20+40+80=140 次检索，但实际第 1 轮通常就够
- L1 缓存不变：缓存的是最终精排后的结果，不是中间检索结果

---

## 6. 实施计划

### 6.1 里程碑

| 阶段 | 内容 | 预估时间 | 依赖 |
|------|------|----------|------|
| 阶段 1 | M6-1 RRF 参数可配置化 | 0.5 天 | 无 |
| 阶段 2 | M6-2 Chunk 文本匹配拼接 | 0.5 天 | 无 |
| 阶段 3 | M6-3 FAQ 负向问题过滤 | 1 天 | 无 |
| 阶段 4 | M6-4 迭代检索扩召回 | 0.5 天 | 无 |
| 阶段 5 | 集成验证 + 单测 | 0.5 天 | 1-4 |
| 阶段 6 | 前端配置页 + 联调 | 0.5 天 | 1, 3 |
| 合计 | | 3.5 天 | |

### 6.2 并行性

M6-1 ~ M6-4 之间**无代码依赖**，可以并行开发。M6-1 的前端配置页和 M6-3 的 QA 生成 prompt 可以同时进行。

### 6.3 验收标准

| 编号 | 验收项 |
|------|--------|
| M6-1 | 修改租户的 RRF 参数后，检索结果排序发生变化；恢复默认后行为与当前一致 |
| M6-2 | 相邻命中块拼接后无内容重复/断裂；单块/不相邻块不受影响 |
| M6-3 | 用户 query 精确匹配负向问题时对应 chunk 被剔除；无负向问题的 chunk 不受影响 |
| M6-4 | 召回不足时自动翻倍 TopK 重试；召回足够时单轮即停；TopK 达上限时停止 |

### 6.4 风险

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| M6-1 参数配置不合理导致检索质量下降 | 中 | 中 | 默认值与当前一致，用户不修改则无变化 |
| M6-2 文本匹配误匹配导致丢字 | 低 | 中 | 最小匹配长度 12 rune，找不到匹配时原样拼接 |
| M6-3 负向问题生成质量差 | 中 | 低 | LLM 生成可人工审核；精确匹配不误伤 |
| M6-4 迭代检索增加延迟 | 中 | 中 | 最大 3 轮，实际第 1 轮通常够；可配置关闭 |

---

## 7. 不移植项及原因

| WeKnora 能力 | 不移植原因 |
|-------------|-----------|
| 分组 fan-out + errgroup | 无多 VectorStore 场景，单库检索够用 |
| 引擎分数归一化（EngineAwareNormalizer） | 当前 pgvector + ES 不存在跨引擎比较问题 |
| Web 搜索 RAG 压缩 | 实现复杂度高，无明确产品需求 |
| 结果增强（关联块） | `RelationChunks` 字段我们没有，需要额外设计 |
| 多引擎支持 | 当前只用了 pgvector + ES，不需要 |
| 迭代检索扩召回 | 召回不足时翻倍 TopK 重试，与 query expansion 功能重叠，暂不移植 |
| 引擎分数归一化（EngineAwareNormalizer） | 当前 pgvector + ES 不存在跨引擎比较问题 |
