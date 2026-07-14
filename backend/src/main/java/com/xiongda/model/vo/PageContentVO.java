package com.xiongda.model.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 文档单页内容（供前端预览真实翻页，M4-4 增强）。
 *
 * <p>PDF 为 PyMuPDF 提取的真实页码；DOCX / TXT / MD 为按 CHARS_PER_PAGE 估算页码，
 * 与问答引用来源的页码口径一致。</p>
 */
@Data
public class PageContentVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 页码（从 1 开始）。*/
    private int pageNo;

    /** 该页提取文本。*/
    private String text;
}
