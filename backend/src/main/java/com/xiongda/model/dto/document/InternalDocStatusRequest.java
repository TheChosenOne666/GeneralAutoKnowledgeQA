package com.xiongda.model.dto.document;

import lombok.Data;

import java.io.Serializable;

/**
 * AI 服务回调文档状态请求（内部接口，由 Python 在各处理阶段边界调用）。
 *
 * <p>仅承载状态推进所需字段；最终 ready/failed 仍由 Java 依据 Python 同步返回结果落库，
 * 此处回调用于实时透出 解析/检索/优化 中间阶段，避免界面长时间停滞在「解析中」。</p>
 */
@Data
public class InternalDocStatusRequest implements Serializable {

    /** 文档 ID。 */
    private Long docId;

    /** 目标状态（须为 DocStatusEnum 中的合法 value）。 */
    private String status;

    /** 分块数（可选，仅部分阶段回填）。 */
    private Integer chunkCount;

    /** 错误信息（可选）。 */
    private String errorMsg;

    /** 是否模型配置错误（可选）。 */
    private Boolean modelConfigError;

    /** 文档提取全文（可选，M5-1：optimizing 阶段经回调回填，替代旧同步返回）。 */
    private String content;
}
