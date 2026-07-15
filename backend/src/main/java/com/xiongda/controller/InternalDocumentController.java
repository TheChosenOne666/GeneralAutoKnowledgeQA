package com.xiongda.controller;

import com.xiongda.common.BaseResponse;
import com.xiongda.common.ResultUtils;
import com.xiongda.model.dto.document.InternalDocStatusRequest;
import com.xiongda.model.enums.DocStatusEnum;
import com.xiongda.service.DocumentService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部文档状态回调接口 — 供 Python AI 服务在处理阶段边界推进文档状态。
 *
 * <p>该接口不经过用户登录鉴权（由 Java 后端于内网调用 Python，Python 反向回推状态），
 * 仅做状态合法性校验，防止非法状态值写入。最终 ready/failed 仍由 Java 依据 Python
 * 同步返回结果落库，此处仅用于实时透出中间阶段。</p>
 */
@RestController
@RequestMapping("/api/internal")
public class InternalDocumentController {

    @Resource
    private DocumentService documentService;

    /**
     * 更新文档处理状态（Python 回调）。
     */
    @PostMapping("/document/status")
    public BaseResponse<Boolean> updateDocumentStatus(@RequestBody InternalDocStatusRequest request) {
        // 仅接受枚举内的合法状态，拒绝非法值写入
        DocStatusEnum statusEnum = DocStatusEnum.getEnumByValue(request.getStatus());
        if (statusEnum == null) {
            return ResultUtils.success(false);
        }
        boolean updated = documentService.updateDocumentStatus(
                request.getDocId(),
                request.getStatus(),
                request.getChunkCount(),
                request.getErrorMsg(),
                request.getModelConfigError());
        return ResultUtils.success(updated);
    }
}
