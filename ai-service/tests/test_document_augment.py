"""文档处理 检索/优化 阶段单测（对齐 业界 finalizing（异步增强） 持久化队列增强）。

验证：
- process() 向量化后立即 store 原始块并返回 optimizing，并把增强任务入持久化队列；
- worker(_run_augment_task) 从向量库读回原始块重建、生成 qa 增强块全量 store 并回调 ready；
- 任务取消（文档已删）时 worker 跳过且不回调 ready；
- 原始块已不存在（文档已删）时 worker 跳过；
- _parse_qa_json 容错解析。

注：项目未引入 pytest-asyncio，async 协程用 asyncio.run 驱动；增强任务经持久化队列
（测试中用内存版 _FakeQueue 替代 Redis 队列）由 worker 消费。
"""
import asyncio
import os
import tempfile

from unittest.mock import AsyncMock

import pytest

from services import document_processor as dp_module
from services.document_processor import document_processor, _parse_qa_json
from services.model_config import ModelConfigError


def _write_tmp(suffix: str, content: str) -> str:
    fd, path = tempfile.mkstemp(suffix=suffix)
    with os.fdopen(fd, "wb") as f:
        f.write(content.encode("utf-8"))
    return path


VEC = [0.1, 0.2, 0.3]

ORIGINAL_CONTENT = "熊答是企业知识问答助手，支持 RAG 检索与智能问答。" * 100


class _FakeQueue:
    """内存版增强队列，供测试验证 enqueue/ack/is_cancelled。"""

    def __init__(self):
        self.queue: list[dict] = []
        self.processing: list[dict] = []
        self.cancelled: set[str] = set()

    async def enqueue(self, task: dict) -> None:
        self.queue.append(task)

    async def dequeue(self) -> dict | None:
        if not self.queue:
            return None
        t = self.queue.pop(0)
        self.processing.append(t)
        return t

    async def ack(self, task: dict) -> None:
        if task in self.processing:
            self.processing.remove(task)

    async def mark_cancelled(self, doc_id: str) -> None:
        self.cancelled.add(doc_id)

    async def is_cancelled(self, doc_id: str) -> bool:
        return doc_id in self.cancelled


@pytest.fixture
def patched(monkeypatch):
    """一次性打桩 embed_batch / store_chunks / get_original_chunks / complete / notify / 队列。"""
    stored: dict = {}
    notifies: list = []
    stages: list = []
    stored["stages"] = stages
    fake_q = _FakeQueue()

    async def fake_embed_batch(texts, cfg=None, client=None):
        return [list(VEC) for _ in texts]

    async def fake_store(chunks, kb_id, doc_id, tenant_id):
        stored.setdefault("calls", 0)
        stored["calls"] += 1
        stored["last"] = (chunks, kb_id, doc_id, tenant_id)

    async def fake_complete(messages, model=None, cfg=None, client=None):
        # 按 system prompt 关键字返回对应 JSON（M5-7 四类扩展增强各自期望不同结构）
        system = messages[0]["content"]
        if "摘要" in system:
            return "熊答是企业知识问答助手。"
        if "Auto-Wiki" in system:
            return '["条目A：熊答定义", "条目B：RAG 检索"]'
        if "实体关系" in system:
            return '[{"subject":"熊答","predicate":"属于","object":"企业知识库"}]'
        if "检索式问题" in system:
            return '["什么是熊答？", "熊答支持什么能力？"]'
        return '{"question": "什么是熊答？", "answer": "熊答是企业知识问答助手。"}'

    async def fake_notify(doc_id, status, client=None, **kwargs):
        # 仅记录 (doc_id, status) 供既有断言使用；**kwargs 吸收新增的 content/chunk_count
        notifies.append((doc_id, status))

    async def fake_notify_stage(doc_id, stage, status, **kwargs):
        # M5-4：记录阶段事件（stage, status, metrics）供断言；**kwargs 吸收计时/错误字段
        stages.append({"doc_id": doc_id, "stage": stage, "status": status, **kwargs})

    async def fake_get_original_chunks(doc_id):
        # 模拟从向量库读回原始块（process 已 store 原始块）
        return [{"content": ORIGINAL_CONTENT, "chunk_index": 0, "page": 1, "source": "x.txt"}]

    async def fake_get_parents_by_doc(doc_id):
        # M5-5：增强重建时读回父块透传；本用例不验证父块，返回空列表避免触碰真实 PG
        return []

    monkeypatch.setattr(dp_module.embedding_service, "embed_batch", fake_embed_batch)
    monkeypatch.setattr(dp_module.vector_store_module.vector_store_service, "store_chunks", fake_store)
    monkeypatch.setattr(
        dp_module.vector_store_module.vector_store_service, "get_original_chunks", fake_get_original_chunks
    )
    monkeypatch.setattr(
        dp_module.vector_store_module.vector_store_service, "get_parents_by_doc", fake_get_parents_by_doc
    )
    monkeypatch.setattr(dp_module.llm_service, "complete", fake_complete)
    monkeypatch.setattr(dp_module, "notify_document_status", fake_notify)
    monkeypatch.setattr(dp_module, "notify_stage", fake_notify_stage)
    monkeypatch.setattr(dp_module.augment_queue, "enqueue", fake_q.enqueue)
    monkeypatch.setattr(dp_module.augment_queue, "ack", fake_q.ack)
    monkeypatch.setattr(dp_module.augment_queue, "is_cancelled", fake_q.is_cancelled)
    # M5-3：主流程 process() 取消守卫改用 process_queue 取消集，默认未取消（正常流程用例）；
    # 取消相关用例各自覆盖 patch dp_module.process_queue.is_cancelled。
    async def _not_cancelled(doc_id):
        return False
    monkeypatch.setattr(dp_module.process_queue, "is_cancelled", _not_cancelled)
    monkeypatch.setattr(dp_module.settings, "enable_qa_augment", True)
    monkeypatch.setattr(dp_module.settings, "qa_max_pairs", 20)
    monkeypatch.setattr(dp_module.settings, "qa_concurrency", 5)
    return stored, notifies, fake_q


def _run(coro):
    return asyncio.run(coro)


def test_process_stores_original_and_enqueues(patched):
    stored, notifies, fake_q = patched
    path = _write_tmp(".txt", ORIGINAL_CONTENT)
    try:
        result = _run(document_processor.process(path, "txt", "kb1", "doc1", "t1", None, None))
    finally:
        os.unlink(path)

    assert result["status"] == "optimizing"
    assert result["chunk_count"] > 0
    # 仅 store 一次原始块（无 qa）
    assert stored["calls"] == 1
    assert all(c["metadata"].get("chunk_type") != "qa" for c in stored["last"][0])
    # 状态回调 retrieving + optimizing
    assert "retrieving" in [s for _, s in notifies]
    assert "optimizing" in [s for _, s in notifies]
    # 增强任务已入持久化队列
    assert len(fake_q.queue) == 1
    assert fake_q.queue[0]["doc_id"] == "doc1"


def test_process_emits_stage_timeline(patched):
    """M5-4：process() 应 emitting parsing/chunking/embedding/indexing 阶段 done；worker 再 emitting optimizing done。"""
    stored, notifies, fake_q = patched
    path = _write_tmp(".txt", ORIGINAL_CONTENT)
    try:
        result = _run(document_processor.process(path, "txt", "kb1", "docStage", "t1", None, None))
    finally:
        os.unlink(path)
    assert result["status"] == "optimizing"

    stages = stored["stages"]
    done_stages = [s["stage"] for s in stages if s["status"] == "done"]
    for expected in ("parsing", "chunking", "embedding", "indexing"):
        assert expected in done_stages, f"缺少阶段 {expected}"

    # embedding 阶段应携带耗时与指标（chunkCount）
    embedding = next(s for s in stages if s["stage"] == "embedding" and s["status"] == "done")
    assert embedding.get("elapsed_ms") is not None
    assert embedding.get("metrics", {}).get("chunkCount") == result["chunk_count"]

    # 消费增强任务 → optimizing 阶段 done（含 enhancedCount 指标）
    _run(document_processor._run_augment_task(fake_q.queue[0]))
    opt = next(s for s in stored["stages"] if s["stage"] == "optimizing" and s["status"] == "done")
    assert opt.get("metrics", {}).get("enhancedCount", 0) >= 1


def test_worker_augments_and_notifies_ready(patched):
    stored, notifies, fake_q = patched
    task = {"doc_id": "doc2", "kb_id": "kb1", "tenant_id": "t1",
            "file_path": "x", "file_type": "txt", "ai_config": None}
    _run(document_processor._run_augment_task(task))

    # worker store 一次（原始重建 + qa 全量 + M5-7 扩展增强），含 qa 块
    assert stored["calls"] == 1
    qa_chunks = [c for c in stored["last"][0] if c["metadata"].get("chunk_type") == "qa"]
    assert len(qa_chunks) >= 1
    assert qa_chunks[0]["content"].startswith("Q: ")
    # M5-7：扩展增强块（summary/question/wiki/entity）一并入库
    ext_types = {c["metadata"].get("chunk_type") for c in stored["last"][0]}
    assert {"summary", "question", "wiki", "entity"} <= ext_types, ext_types
    entity = [c for c in stored["last"][0] if c["metadata"].get("chunk_type") == "entity"][0]
    assert entity["metadata"].get("triple") == {
        "subject": "熊答", "predicate": "属于", "object": "企业知识库"
    }
    # 回调 ready（文档已可检索 → 增强完成）
    assert "ready" in [s for _, s in notifies]
    # 任务从 processing 移除
    assert task not in fake_q.processing


def test_worker_skips_cancelled(patched):
    stored, notifies, fake_q = patched
    _run(fake_q.mark_cancelled("doc3"))
    task = {"doc_id": "doc3", "kb_id": "kb1", "tenant_id": "t1",
            "file_path": "x", "file_type": "txt", "ai_config": None}
    _run(document_processor._run_augment_task(task))

    # 取消：不 store、不回调 ready、任务仍被 ack
    assert stored.get("calls", 0) == 0
    assert "ready" not in [s for _, s in notifies]
    assert task not in fake_q.processing


def test_worker_skips_deleted_doc(patched, monkeypatch):
    stored, notifies, fake_q = patched
    # 原始块已不存在（文档被删，向量已清）
    async def empty(doc_id):
        return []

    monkeypatch.setattr(dp_module.vector_store_module.vector_store_service, "get_original_chunks", empty)
    task = {"doc_id": "doc4", "kb_id": "kb1", "tenant_id": "t1",
            "file_path": "x", "file_type": "txt", "ai_config": None}
    _run(document_processor._run_augment_task(task))

    assert stored.get("calls", 0) == 0
    assert "ready" not in [s for _, s in notifies]
    assert task not in fake_q.processing


def test_worker_includes_multimodal_when_images(patched, monkeypatch):
    """M5-6：文档含图片时，worker 抽取图片并产出 ocr/image_caption 块一并入库。"""
    stored, notifies, fake_q = patched

    async def fake_extract_images(fp, ft):
        return [{"bytes": b"imgdata", "page": 2, "ext": "png"}]

    async def fake_complete(messages, model=None, cfg=None, client=None):
        # 多模态消息：content 为 parts 列表（含 image_url），区别于常规文本 system prompt
        content = messages[0]["content"]
        if isinstance(content, list):
            return '{"ocr": "图中为薪酬结构表", "caption": "展示月度薪资构成的图表"}'
        return '{"question": "什么是熊答？", "answer": "熊答是企业知识问答助手。"}'

    monkeypatch.setattr(document_processor, "extract_images", fake_extract_images)
    monkeypatch.setattr(dp_module.llm_service, "complete", fake_complete)

    # 真实临时文件，使 _generate_multimodal_augment 的 os.path.exists 守卫通过
    fd, img_path = tempfile.mkstemp(suffix=".pdf")
    os.close(fd)
    try:
        task = {"doc_id": "docMM", "kb_id": "kb1", "tenant_id": "t1",
                "file_path": img_path, "file_type": "pdf", "ai_config": None}
        _run(document_processor._run_augment_task(task))
    finally:
        os.unlink(img_path)

    types = {c["metadata"].get("chunk_type") for c in stored["last"][0]}
    assert "ocr" in types
    assert "image_caption" in types
    ocr = [c for c in stored["last"][0] if c["metadata"].get("chunk_type") == "ocr"][0]
    assert ocr["content"] == "图中为薪酬结构表"
    assert ocr["metadata"]["page"] == 2
    # 多模态块也被向量化（参与检索）
    assert ocr["embedding"] is not None


def test_parse_qa_json_robust():
    assert _parse_qa_json('{"question": "q", "answer": "a"}') == {"question": "q", "answer": "a"}
    # markdown 代码块包裹
    assert _parse_qa_json('```json\n{"question": "q", "answer": "a"}\n```') == {
        "question": "q",
        "answer": "a",
    }
    # 夹杂多余文字
    assert _parse_qa_json('好的：{"question": "q", "answer": "a"} 完成') == {
        "question": "q",
        "answer": "a",
    }
    # 缺字段
    assert _parse_qa_json('{"question": "q"}') is None
    assert _parse_qa_json("not json") is None


def test_process_cancelled_before_embed(patched, monkeypatch):
    """取消检查点（嵌入前）：已取消则不消耗 Embedding、不入库、不入队，回调 cancelled。"""
    stored, notifies, fake_q = patched

    async def always_cancelled(doc_id):
        return True

    # M5-3：嵌入前检查点改用主流程 process_queue 取消集（xiongda:doc:cancelled）
    monkeypatch.setattr(dp_module.process_queue, "is_cancelled", always_cancelled)

    path = _write_tmp(".txt", ORIGINAL_CONTENT)
    try:
        result = _run(document_processor.process(path, "txt", "kb1", "docX", "t1", None, None))
    finally:
        os.unlink(path)

    assert result["status"] == "cancelled"
    # 未 store、未入增强队
    assert stored.get("calls", 0) == 0
    assert len(fake_q.queue) == 0
    assert "cancelled" in [s for _, s in notifies]
    assert "optimizing" not in [s for _, s in notifies]


def test_process_cancelled_after_store_cleans_vectors(patched, monkeypatch):
    """取消检查点④（入库后）：已取消则清理已写向量 + 回调 cancelled + 不返回 optimizing。"""
    stored, notifies, fake_q = patched
    deletes = []

    async def fake_delete(doc_id):
        deletes.append(doc_id)

    monkeypatch.setattr(
        dp_module.vector_store_module.vector_store_service, "delete_by_doc", fake_delete
    )

    async def cancel_after_store(doc_id):
        # 以「向量是否已入库」为判据：嵌入前 / 索引前（store 未调用）不取消，
        # 入库后（store 已调用一次）才取消，精准命中检查点④。
        return stored.get("calls", 0) >= 1

    monkeypatch.setattr(dp_module.process_queue, "is_cancelled", cancel_after_store)

    path = _write_tmp(".txt", ORIGINAL_CONTENT)
    try:
        result = _run(document_processor.process(path, "txt", "kb1", "docY", "t1", None, None))
    finally:
        os.unlink(path)

    assert result["status"] == "cancelled"
    # 嵌入前未取消，原始块会 store 一次
    assert stored.get("calls", 0) == 1
    # 入库后取消 → 清理已写向量
    assert "docY" in deletes
    assert "cancelled" in [s for _, s in notifies]
    assert "optimizing" not in [s for _, s in notifies]
    # 未入增强队
    assert len(fake_q.queue) == 0


def test_process_cancelled_before_index(patched, monkeypatch):
    """取消检查点③（索引/入库前，M5-3 新增）：Embedding 完成后、store 之前取消，
    直接放弃且不写向量（未入库无需清理），回调 cancelled、不返回 optimizing。"""
    stored, notifies, fake_q = patched
    deletes = []

    async def fake_delete(doc_id):
        deletes.append(doc_id)

    monkeypatch.setattr(
        dp_module.vector_store_module.vector_store_service, "delete_by_doc", fake_delete
    )

    calls = {"n": 0}

    async def cancel_before_index(doc_id):
        calls["n"] += 1
        # 嵌入前（第 1 次）未取消；索引前（第 2 次）已取消
        return calls["n"] >= 2

    monkeypatch.setattr(dp_module.process_queue, "is_cancelled", cancel_before_index)

    path = _write_tmp(".txt", ORIGINAL_CONTENT)
    try:
        result = _run(document_processor.process(path, "txt", "kb1", "docZ", "t1", None, None))
    finally:
        os.unlink(path)

    assert result["status"] == "cancelled"
    # 索引前取消：未写向量、无需清理
    assert stored.get("calls", 0) == 0
    assert deletes == []
    assert "cancelled" in [s for _, s in notifies]
    assert "optimizing" not in [s for _, s in notifies]
    assert len(fake_q.queue) == 0
