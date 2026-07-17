package com.xiongda.model.dto.document;

import lombok.Data;

import java.io.Serializable;

/**
 * AI 服务回调文档处理阶段请求（内部接口，由 Python 在各处理阶段边界调用）。
 *
 * <p>M5-4 阶段化 span 时间线追踪：Python 把 解析(parsing)/分块(chunking)/向量化(embedding)/
 * 入库(indexing)/增强(optimizing) 拆成带时间线与指标的阶段事件，经本接口落库到文档的
 * {@code process_stages} 字段，前端据此展示细粒度进度与失败定位。</p>
 */
@Data
public class InternalDocStageRequest implements Serializable {

    /** 文档 ID。 */
    private Long docId;

    /** 阶段名（parsing/chunking/embedding/indexing/optimizing）。 */
    private String stage;

    /** 阶段状态（active/done/failed）。 */
    private String status;

    /** 阶段开始时间（epoch 毫秒，可选）。 */
    private Long startedAt;

    /** 阶段结束时间（epoch 毫秒，可选）。 */
    private Long endedAt;

    /** 阶段耗时（毫秒，可选）。 */
    private Long elapsedMs;

    /** 失败原因（status=failed 时回填，可选）。 */
    private String error;

    /** 阶段指标 JSON 字符串（如 vectors_written / chunk_count / elapsed_ms，可选）。 */
    private String metrics;
}
