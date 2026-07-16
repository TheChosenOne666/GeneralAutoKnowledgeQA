package com.xiongda.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 文档视图对象。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Data
public class DocumentVO implements Serializable {

    private Long id;

    private Long kbId;

    private String filename;

    private String fileType;

    private Long fileSize;

    /**
     * pending / parsing / embedding / ready / failed
     */
    private String status;

    private Integer chunkCount;

    private String errorMsg;

    /**
     * 是否因 AI 模型配置错误导致失败（M3-3，前端据此提示重配）。
     */
    private Boolean modelConfigError;

    /**
     * 是否因模型额度不足 / 被限流导致失败（区别于配置错误，前端提示稍后重试 / 检查额度）。
     */
    private Boolean quotaError;

    private Date createTime;
}
