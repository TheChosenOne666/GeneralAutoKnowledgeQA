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

    private Date createTime;
}
