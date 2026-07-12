package com.xiongda.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * AI 模型配置实体 — LLM / Embedding / Rerank。
 * userId 为 null 表示租户级默认配置。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@TableName(value = "ai_config")
@Data
public class AiConfig implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;

    /**
     * null = 租户默认配置
     */
    private Long userId;

    // LLM
    private String llmProvider;
    private String llmModel;
    private String llmApiKey;
    private String llmBaseUrl;
    private Double llmTemperature;
    private Integer llmMaxTokens;

    /**
     * LLM 多模型列表（JSON 数组字符串，如 ["deepseek-v3","deepseek-r1"]）。
     * llmModel 为默认选中的模型；llmModels 为对话页下拉可切换的全部模型。
     */
    private String llmModels;

    // Embedding
    private String embeddingProvider;
    private String embeddingModel;
    private String embeddingApiKey;
    private String embeddingBaseUrl;
    private Integer embeddingDimension;

    // Rerank（可选）
    private String rerankProvider;
    private String rerankModel;
    private String rerankApiKey;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer isDelete;
}
