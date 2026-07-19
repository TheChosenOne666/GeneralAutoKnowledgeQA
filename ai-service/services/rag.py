"""RAG 检索服务 — 混合检索（向量 + BM25）+ 向量主导融合 + LLM 重排精排。

使用全局 vector_store_service（与文档处理为同一实例，内存数据一致）：
1. Query 向量化（Embedding）
2. 向量检索（Top-K=20）
3. BM25 关键词检索（Top-K=20）
4. 向量主导融合（语义优先，BM25 仅补充不足）
5. LLM 重排（用已配置 LLM 对候选块打 0~1 相关性分并重排，抑制弱向量跨领域串味；
   对标业界成熟方案 Rerank 意图但复用已有 LLM，不写死领域词、不新增服务）
"""

import hashlib
import json
import re
from dataclasses import asdict

import httpx
from loguru import logger

from core.config import settings
from core.redis_client import get_redis
from services import vector_store as vector_store_module
from services.embedding import embedding_service
from services.llm import llm_service
from services.model_config import ModelConfig, ModelConfigError
from services.query_rewrite import expand_query, rewrite_query
from services.vector_store import AUGMENT_CHUNK_TYPES, RetrievalResult, _bigrams

# 大纲意图关键词（触发 outline 块优先召回，方案C）：问「知识架构/大纲/目录/考点」类问题
OUTLINE_KEYWORDS = (
    "架构", "大纲", "目录", "有哪些", "知识框架", "考点", "知识点",
    "包含哪些", "涵盖", "体系", "主要学", "重点", "模块",
)


class RagService:
    """RAG 检索服务。"""

    def _cache_key(self, query: str, kb_ids: list[str], tenant_id: str, top_n: int) -> str:
        """L1 缓存 key：retrieval:{tenant_id}:{q_hash}（跨会话 tenant 级）。

        纳入 rerank 方式与候选池大小 + top_n，确保算法 / 范围变更后旧缓存自动失效。
        """
        kb_part = ",".join(sorted(kb_ids)) if kb_ids else ""
        q_hash = hashlib.md5(
            f"{query}|{kb_part}|{settings.retrieval_rerank_method}|"
            f"{settings.retrieval_rerank_top_k}|{top_n}|"
            f"{settings.retrieval_parent_context}".encode("utf-8")
        ).hexdigest()
        return f"retrieval:{tenant_id}:{q_hash}"

    async def retrieve(
        self,
        query: str,
        kb_ids: list[str],
        tenant_id: str,
        top_n: int = 5,
        cfg: ModelConfig | None = None,
        enhance: bool = False,
        retrieval_config: str | None = None,
    ) -> list[RetrievalResult]:
        """完整检索流程：混合检索 → RRF 融合 → Rerank 精排。

        带 L1 检索结果缓存：相同 tenant + 问题（含知识库范围）命中时直接返回，
        跳过向量 / BM25 / RRF / Rerank，但仍交由 LLM 实时生成答案（不过时）。

        Args:
            query: 用户问题
            kb_ids: 知识库 ID 列表
            tenant_id: 租户 ID
            top_n: 最终返回的结果数
            enhance: 普通问答增强（对齐 业界 KnowledgeQA 方案）。为 True 时先对 query
                做 LLM 改写（rewrite），主检索召回不足时再做 LLM 扩展检索（expansion）
                并 RRF 合并。仅 rag 模式开启；Agent 模式不传（避免二次改写 LLM 自生成
                的子查询，画蛇添足）。
            retrieval_config: M6-1 租户级检索配置 JSON 字符串，覆盖 settings 默认值。
                None 时走 settings .env 默认值。

        Returns:
            精排后的检索结果列表
        """
        cache_key = self._cache_key(query, kb_ids, tenant_id, top_n)
        try:
            r = await get_redis()
            cached = await r.get(cache_key)
            if cached is not None:
                logger.info(f"L1 检索缓存命中: {cache_key}")
                return [RetrievalResult(**d) for d in json.loads(cached)]
        except Exception as e:
            logger.warning(f"读取检索缓存失败，降级实时检索: {e}")

        # M6-1：解析租户级检索配置，覆盖 settings 默认值
        rc: dict = {}
        if retrieval_config:
            try:
                rc = json.loads(retrieval_config)
                logger.info(f"[M6-1] 租户检索配置已加载: {rc}")
            except (json.JSONDecodeError, TypeError) as e:
                logger.warning(f"[M6-1] 检索配置解析失败，走默认值: {e}")
        # 辅助函数：优先用 rc 中的值，否则用 settings
        def _rc(key: str, default):
            v = rc.get(key)
            return v if v is not None else default

        # A1 query rewrite：口语化问题改写为检索友好 query（仅 enhance 且开启开关时）
        search_query = query
        if enhance and settings.enable_query_rewrite:
            try:
                rewritten = await rewrite_query(query, cfg=cfg)
            except ModelConfigError:
                raise
            except Exception as e:
                logger.warning(f"query rewrite 失败，降级原话检索: {e}")
                rewritten = query
            if rewritten and rewritten != query:
                logger.info(f"[检索诊断] query 改写: {query!r} -> {rewritten!r}")
                search_query = rewritten

        vec_results, bm25_results, best_vec, best_bm25 = await self._search_core(
            search_query, kb_ids, tenant_id, cfg
        )

        # 方案C：大纲意图检测（架构/大纲/目录/考点类问题优先召回章节标题块），
        # 让 LLM 基于文档真实章节主题归纳知识框架，而非仅命中概述页。
        is_outline = self._is_outline_query(query)
        outline_results: list[RetrievalResult] = []
        if is_outline:
            try:
                query_vec = await embedding_service.embed_text(query, cfg)
                ov = await vector_store_module.vector_store_service.search(
                    query_vec, kb_ids, tenant_id,
                    top_k=settings.retrieval_outline_top_n, chunk_types=["outline"],
                )
                ob = await vector_store_module.vector_store_service.keyword_search(
                    query, kb_ids, tenant_id,
                    top_k=settings.retrieval_outline_top_n, chunk_types=["outline"],
                )
                outline_results = (
                    self._merge_vector_dominant(ov, ob, settings.retrieval_outline_top_n, query)
                    if ov else ob
                )
            except Exception as e:
                logger.warning(f"[检索诊断] 大纲块召回失败，降级常规检索: {e}")
                outline_results = []


        # A2 query expansion：主检索召回不足时，生成扩展 query 再检索并合并兜底
        if enhance and settings.enable_query_expansion and len(vec_results) < settings.retrieval_expansion_min:
            try:
                expansions = await expand_query(query, cfg=cfg)
            except Exception as e:
                logger.warning(f"query expansion 失败，跳过: {e}")
                expansions = []
            for eq in expansions:
                v2, b2, bv2, bb2 = await self._search_core(eq, kb_ids, tenant_id, cfg)
                if v2:
                    vec_results = vec_results + v2
                    bm25_results = bm25_results + b2
                    best_vec = max(best_vec, bv2)
                    best_bm25 = max(best_bm25, bb2)
                    logger.info(f"[检索诊断] expansion query={eq!r} 命中={len(v2)}")

        # 5. 融合策略：向量（语义）可用时主导，BM25 仅补充不足，抑制关键词噪声
        # 重排候选池：开启重排时融合先用更大池（rerank_top_k），重排再精排取 top_n，
        # 解决「模糊问句真正相关块未进前 top_n，LLM 只在这 top_n 内打分致全 0」的召回回归。
        rerank_method = settings.retrieval_rerank_method
        rerank_key = (cfg.rerank_api_key if cfg and cfg.rerank_api_key else None) or settings.rerank_api_key
        will_rerank = rerank_method in ("llm", "api") and (
            (rerank_method == "llm" and cfg and cfg.has_llm())
            or (rerank_method == "api" and rerank_key)
        )
        rerank_pool = max(top_n, settings.retrieval_rerank_top_k) if will_rerank else top_n
        vec_min_rel = _rc("vector_min_relevance", settings.retrieval_vector_min_relevance)
        if settings.retrieval_vector_dominant and vec_results and best_vec >= vec_min_rel:
            merged = self._merge_vector_dominant(vec_results, bm25_results, rerank_pool, search_query)
        else:
            merged = bm25_results[:rerank_pool]

        # 5.5 排除增强块作为引用来源：chunk_type ∈ AUGMENT_CHUNK_TYPES（qa/question/
        # summary/wiki/entity）由 LLM 生成、source 通常为空/非原文，仅用于提升检索召回
        # 的语义桥接，不应作为引用来源（见 :data:`core.config.retrieval_exclude_augment_blocks`）。
        # 仅当排除后仍非空才替换，避免「候选全是增强块」时误判为无相关文档。
        if settings.retrieval_exclude_augment_blocks or settings.retrieval_exclude_qa_blocks:
            non_aug = [r for r in merged if r.chunk_type not in AUGMENT_CHUNK_TYPES]
            if non_aug:
                merged = non_aug

        # 5.6 LLM 重排精排（领域无关：用已配置 LLM 对候选块按相关性打分排序，
        # 抑制弱向量模型对短 query 的跨领域串味，如问「后端」却返回「前端」块）。
        rerank_applied = False
        if will_rerank:
            if rerank_method == "llm":
                merged, rerank_applied = await self._rerank_with_llm(search_query, merged, top_n, cfg)
            else:  # api
                merged, rerank_applied = await self._rerank(
                    search_query,
                    merged,
                    top_n,
                    rerank_key,
                    (cfg.rerank_base_url if cfg else None) or settings.rerank_base_url,
                    (cfg.rerank_model if cfg else None) or settings.rerank_model,
                )
        # 重排阈值过滤（对标业界成熟方案 rerank.go：低于阈值的跨主题块直接剔除，
        # 仅当最优分仍 >= 0.15 时保留 top1 兜底，避免全部误删）。未重排时直接截断 top_n。
        rerank_th = _rc("rerank_min_relevance", settings.retrieval_rerank_min_relevance)
        if rerank_applied:
            kept = [r for r in merged if r.score >= rerank_th]
            if not kept and merged and merged[0].score >= 0.15:
                kept = merged[:1]
            # 重排后只保留过门槛的块；不再回退到未过滤的 merged[:top_n]，
            # 否则门槛过滤形同虚设（全不过门槛时仍会把无关块当来源展示）。
            merged = kept[:top_n]
        else:
            merged = merged[:top_n]

        # 方案C：大纲意图将 outline 块保送结果前部（短标题块不应被 rerank 阈值剔除），
        # 并补充常规结果，使 LLM 既能看到知识框架标题又能看到正文细节。
        if is_outline and outline_results:
            sent: set = set()
            combined: list[RetrievalResult] = []
            for r in outline_results[: settings.retrieval_outline_top_n]:
                key = (r.doc_id, r.kb_id, r.chunk_index)
                sent.add(key)
                combined.append(r)
            for r in merged:
                key = (r.doc_id, r.kb_id, r.chunk_index)
                if key in sent:
                    continue
                sent.add(key)
                combined.append(r)
            merged = combined[:top_n]
            rerank_applied = False  # 大纲块保送，不应用 rerank 阈值过滤

        # 诊断日志：打印召回信号，便于排查「知识库有内容却检索不到」
        logger.info(
            f"[检索诊断] query={query!r} search_query={search_query!r} kb_ids={kb_ids} tenant={tenant_id} "
            f"vec_top={best_vec:.3f} bm25_top={best_bm25:.3f} "
            f"merged={len(merged)} outline={'on' if is_outline else 'off'} "
            f"rerank={'on(' + rerank_method + ')' if rerank_applied else 'off'}"
        )

        # 7. 相对相关性过滤：剔除与最优分差距过大的跨主题噪声（基于最终 top-N）
        # 大纲意图下保送 outline 块，跳过此过滤（避免短标题块被误剔除）
        if settings.retrieval_relevance_gate and merged and not is_outline:
            best = merged[0].score
            rel_ratio = _rc("relative_ratio", settings.retrieval_relative_ratio)
            floor = max(best * rel_ratio, _rc("vector_min_relevance", settings.retrieval_vector_min_relevance))
            filtered = [r for r in merged if r.score >= floor]
            if filtered:
                merged = filtered

        # 8. 相关性门槛（全局信号兜底）：全部不相关时视为「无相关文档」
        # 大纲意图下保送 outline 块，跳过此过滤
        if settings.retrieval_relevance_gate and merged and not is_outline and not self._is_relevant(
            merged, rerank_applied, best_vec, best_bm25
        ):
            logger.info(
                f"检索相关性不足，视为无相关文档: query={query} "
                f"rerank_applied={rerank_applied} best_vec={best_vec:.3f} best_bm25={best_bm25:.3f}"
            )
            merged = []

        # M5-5 父子分块：命中子块后回溯其父块完整内容（small-to-big），供 LLM 获得更连贯的上下文。
        # best-effort：回溯失败不影响已排好的检索结果。
        if settings.retrieval_parent_context and merged:
            try:
                merged = await vector_store_module.vector_store_service.attach_parents(merged)
            except Exception as e:
                logger.warning(f"[父子分块] 回溯父块内容失败，降级用子块内容: {e}")

        # M6-2：相邻命中块文本匹配拼接 — 同文档 chunk_index 相邻的块按文本后缀匹配去重叠，
        # 避免拼接后内容重复/断裂（在父块回溯之后执行，仅作用于同文档无父块归属的命中块）。
        if settings.enable_chunk_merge and merged:
            try:
                merged = self._merge_adjacent_chunks(merged)
            except Exception as e:
                logger.warning(f"[M6-2 相邻拼接] 失败，降级用原始块: {e}")

        try:
            r = await get_redis()
            await r.set(
                cache_key,
                json.dumps([asdict(x) for x in merged], ensure_ascii=False),
                ex=settings.retrieval_cache_ttl,
            )
        except Exception as e:
            logger.warning(f"写入检索缓存失败，忽略: {e}")
        return merged

    async def _search_core(
        self,
        query: str,
        kb_ids: list[str],
        tenant_id: str,
        cfg: ModelConfig | None,
    ) -> tuple[list[RetrievalResult], list[RetrievalResult], float, float]:
        """单次混合检索：Query 向量化 + 向量检索 + BM25。

        返回 ``(向量结果, BM25结果, 向量最高分, BM25最高分)``。融合策略交由
        :meth:`retrieve` 统一决策（向量可用时主导，BM25 仅补充）。向量不可用
        （Key 失效）时 ``embed_text`` 抛 ``ModelConfigError`` 向上传播，由 chat 路由提示用户重配。
        """
        # 1. Query 向量化
        query_vec = await embedding_service.embed_text(query, cfg)

        # 2. 向量检索 Top-K=20
        vec_results = await vector_store_module.vector_store_service.search(
            query_vec, kb_ids, tenant_id, top_k=20
        )

        # 3. BM25 关键词检索 Top-K=20
        bm25_results = await vector_store_module.vector_store_service.keyword_search(
            query, kb_ids, tenant_id, top_k=20
        )

        best_vec = vec_results[0].score if vec_results else 0.0
        best_bm25 = bm25_results[0].score if bm25_results else 0.0

        return vec_results, bm25_results, best_vec, best_bm25

    async def _rerank(
        self,
        query: str,
        results: list[RetrievalResult],
        top_n: int = 5,
        rerank_api_key: str = "",
        rerank_base_url: str = "",
        rerank_model: str = "",
    ) -> tuple[list[RetrievalResult], bool]:
        """Rerank 精排（调用 OpenAI 兼容 Rerank 接口）。

        返回 ``(精排后结果, 是否真正应用 rerank 分数)``。调用失败（模型名 / API Key 错误）
        抛 :class:`ModelConfigError`，不静默降级。
        """
        if not results:
            return results, False
        documents = [r.content for r in results]
        try:
            async with httpx.AsyncClient(timeout=30.0) as client:
                resp = await client.post(
                    f"{rerank_base_url}/rerank",
                    headers={
                        "Authorization": f"Bearer {rerank_api_key}",
                        "Content-Type": "application/json",
                    },
                    json={
                        "model": rerank_model,
                        "query": query,
                        "documents": documents,
                        "top_n": top_n,
                    },
                )
                resp.raise_for_status()
                data = resp.json().get("results", [])
        except httpx.HTTPError as e:
            raise ModelConfigError(f"Rerank 调用失败（模型名或 API Key 可能错误）：{e}") from e
        ordered = []
        for item in data:
            idx = item.get("index")
            if idx is not None and 0 <= idx < len(results):
                r = results[idx]
                # 写入 rerank 相关性分数（兼容 relevance_score / score 两种字段名）
                r.score = float(item.get("relevance_score", item.get("score", r.score)))
                ordered.append(r)
        # 未返回可用结果则回退 RRF 顺序，并标记未应用 rerank 分数（改用向量/BM25 门槛）
        if not ordered:
            return results[:top_n], False
        return ordered, True

    def _merge_vector_dominant(
        self,
        vec_results: list[RetrievalResult],
        bm25_results: list[RetrievalResult],
        top_n: int,
        search_query: str = "",
    ) -> list[RetrievalResult]:
        """向量主导融合：以向量检索结果为主排序，BM25 仅补充向量不足部分。

        - 向量结果按余弦相似度排序优先入选；
        - 语义平手（向量分与最优分差距 <= ``retrieval_bm25_tie_epsilon``）且词法重合度
          （块内容与查询的 bigram 重合比例）>= ``retrieval_bm25_tie_overlap_min`` 时，按重合度
          加权抬升，让「后端规范」类关键词相关块优先于向量模型略偏的前端/JavaWeb 块
          （差距明显时不触发，保证「后端刚入职」类问题的收敛）；
        - 向量不足 top_n 时，从 BM25 补充强相关（``score >= retrieval_bm25_min_relevance``）且未被向量覆盖的块；
        - 单文档最多 ``retrieval_max_chunks_per_doc`` 个块，避免单文档刷屏；
        - 空 source 块回退到同文档任一非空 source，保证引用来源可读。
        """
        seen: set[tuple] = set()
        per_doc: dict[str, int] = {}
        max_per_doc = settings.retrieval_max_chunks_per_doc

        # 语义平手决胜：用块内容与查询的词法重合度（而非 BM25 物理块匹配，因 BM25 命中的
        # 常是 QA 增强块，与原文块 key 不同），更稳健地识别「含查询关键词」的原文块。
        q_bigrams = set(_bigrams(search_query)) if search_query else set()
        best_vec = vec_results[0].score if vec_results else 0.0
        eps = settings.retrieval_bm25_tie_epsilon
        boost = settings.retrieval_bm25_tie_boost
        overlap_min = settings.retrieval_bm25_tie_overlap_min

        def _lex_overlap(r: RetrievalResult) -> float:
            if not q_bigrams:
                return 0.0
            inter = q_bigrams & set(_bigrams(r.content))
            return len(inter) / len(q_bigrams)

        def _tie_adj(r: RetrievalResult) -> float:
            """平手决胜分数：向量分，平手且词法命中时按重合度加权抬升（仅用于排序，不改真实 score）。"""
            if q_bigrams and best_vec - r.score <= eps:
                ov = _lex_overlap(r)
                if ov >= overlap_min:
                    return r.score + ov * boost
            return r.score

        # 按平手决胜分降序确定入选顺序
        ordered = sorted(vec_results, key=_tie_adj, reverse=True)

        def _accept(r: RetrievalResult) -> bool:
            key = (r.doc_id, r.kb_id, r.chunk_index)
            if key in seen:
                return False
            if per_doc.get(r.doc_id, 0) >= max_per_doc:
                return False
            return True

        def _add(r: RetrievalResult) -> None:
            key = (r.doc_id, r.kb_id, r.chunk_index)
            seen.add(key)
            per_doc[r.doc_id] = per_doc.get(r.doc_id, 0) + 1

        merged: list[RetrievalResult] = []
        for r in ordered:
            if _accept(r):
                _add(r)
                merged.append(r)
            if len(merged) >= top_n:
                break

        if len(merged) < top_n:
            for r in bm25_results:
                if r.score < settings.retrieval_bm25_min_relevance:
                    continue
                if _accept(r):
                    _add(r)
                    merged.append(r)
                if len(merged) >= top_n:
                    break

        # 回填空 source：同 doc_id 用其任一非空 source
        doc_source: dict[str, str] = {}
        for r in merged:
            if r.source and r.doc_id not in doc_source:
                doc_source[r.doc_id] = r.source
        for r in merged:
            if not r.source and r.doc_id in doc_source:
                r.source = doc_source[r.doc_id]
        return merged

    @staticmethod
    def _is_relevant(
        merged: list[RetrievalResult],
        rerank_applied: bool,
        best_vec: float,
        best_bm25: float,
    ) -> bool:
        """检索结果是否相关（达到相关性门槛）。

        配置 rerank 且成功应用：以 rerank 相关性分数为准；否则以向量余弦最高分或
        BM25 最高分为准（两者满足其一即视为相关）。
        """
        if rerank_applied:
            return merged[0].score >= settings.retrieval_rerank_min_relevance
        if best_vec >= settings.retrieval_vector_min_relevance:
            return True
        # 向量不可用（走 BM25 兜底）：只要有 BM25 召回即视为相关，
        # 不要求 retrieval_bm25_min_relevance（那是向量主导时 BM25 补充的强相关门槛）
        return best_bm25 > 0.0

    @staticmethod
    def _is_outline_query(query: str) -> bool:
        """判断问题是否属大纲/架构类意图（应优先召回章节标题 outline 块，方案C）。"""
        return any(kw in query for kw in OUTLINE_KEYWORDS)

    @staticmethod
    def _merge_adjacent_chunks(results: list[RetrievalResult]) -> list[RetrievalResult]:
        """M6-2：同一文档中 chunk_index 相邻的命中块，用文本匹配拼接去除重叠。

        按 doc_id 分组，每组内按 chunk_index 排序，检测相邻性（chunk_index 差 <= 1），
        用 :func:`merge_text_chunks` 拼接 content。保留第一个块的元信息，
        content 替换为拼接后的完整文本。非相邻或单块不受影响。
        """
        from services.chunk_merge import merge_text_chunks

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

    async def _rerank_with_llm(
        self,
        query: str,
        results: list[RetrievalResult],
        top_n: int = 5,
        cfg: ModelConfig | None = None,
    ) -> tuple[list[RetrievalResult], bool]:
        """用已配置的 LLM 对候选块做语义重排（替代写死的领域聚焦 / OpenAI rerank endpoint）。

        把 query + 候选块发给 LLM，要求其逐块给出 0~1 相关性分数，再按分数重排并按阈值过滤，
        比弱向量模型更能判别跨领域片段（如「后端规范」应匹配后端而非前端内容），且不写死任何
        领域词、不新增服务（复用 AI 配置页已填的 LLM）。

        Returns:
            ``(重排后结果, 是否真正应用 LLM 分数)``。LLM 调用失败 / 分数不可解析时回退原顺序并
            标记未应用，由上层按截断逻辑兜底，绝不静默吞错。
        """
        if not results or not cfg or not cfg.has_llm():
            return results, False
        labeled = [f"[{i}] {r.content[:300]}" for i, r in enumerate(results)]
        messages = [
            {
                "role": "system",
                "content": (
                    "你是严格的检索相关性评判器。根据用户问题，对给定文本片段逐一评估相关性，"
                    "给出 0 到 1 之间的分数（1=高度相关且直接回答问题，0=完全无关）。评分务必严格：\n"
                    "1. 仅当片段明确回答或高度契合问题主题时，才给 >=0.6；\n"
                    "2. 仅含部分相同词汇但主题无关（例如问「校招面试重点」却返回「员工入职指南」或"
                    "「常规 Java 笔记」等泛化文档）的片段，必须给 <=0.2；\n"
                    "3.  thematic 偏离（不同业务/不同技术栈）的片段一律 <=0.2。\n"
                    "只输出一个 JSON 数组，每个元素为对应片段的相关性分数（浮点数），"
                    "顺序与输入编号一致。不要输出多余解释。"
                ),
            },
            {
                "role": "user",
                "content": (
                    f"问题：{query}\n\n片段：\n" + "\n".join(labeled) + "\n\n"
                    f"请输出长度为 {len(results)} 的 JSON 数组，例如 [0.9, 0.2, 0.7]，只输出该数组。"
                ),
            },
        ]
        try:
            raw = await llm_service.complete(messages, cfg=cfg)
        except ModelConfigError as e:
            logger.warning(f"[检索诊断][LLM重排] 配置错误，跳过重排: {e}")
            return results, False
        except Exception as e:
            logger.warning(f"[检索诊断][LLM重排] 调用失败，跳过重排: {e}")
            return results, False
        scores = self._parse_rerank_scores(raw, len(results))
        if scores is None:
            logger.warning(f"[检索诊断][LLM重排] 分数解析失败，跳过重排: {raw[:120]}")
            return results, False
        for r, s in zip(results, scores):
            r.score = float(s)
        ordered = [r for _, r in sorted(zip(scores, results), key=lambda x: x[0], reverse=True)]
        logger.info(
            f"[检索诊断][LLM重排] query={query!r} 分数={[round(s, 2) for s in scores]}"
        )
        return ordered, True

    @staticmethod
    def _parse_rerank_scores(raw: str, n: int) -> list[float] | None:
        """从 LLM 输出中提取长度为 ``n`` 的分数数组；格式不符返回 None。"""
        if not raw:
            return None
        m = re.search(r"\[[\s\S]*?\]", raw)
        if not m:
            return None
        try:
            arr = json.loads(m.group(0))
        except json.JSONDecodeError:
            return None
        if not isinstance(arr, list) or len(arr) != n:
            return None
        out: list[float] = []
        for v in arr:
            try:
                out.append(float(v))
            except (TypeError, ValueError):
                return None
        return out

    @staticmethod
    def _rrf(results_list: list[list[RetrievalResult]], k: int = 60) -> list[RetrievalResult]:
        """Reciprocal Rank Fusion：融合多路召回结果并去重。"""
        fused: dict[tuple, dict] = {}
        for results in results_list:
            for rank, r in enumerate(results):
                key = (r.doc_id, r.kb_id, r.chunk_index, r.content)
                entry = fused.setdefault(key, {"result": r, "score": 0.0})
                entry["score"] += 1.0 / (rank + k)
        items = sorted(fused.values(), key=lambda x: x["score"], reverse=True)
        for it in items:
            it["result"].score = round(it["score"], 6)
        return [it["result"] for it in items]


rag_service = RagService()
