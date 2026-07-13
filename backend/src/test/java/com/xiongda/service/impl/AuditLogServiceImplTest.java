package com.xiongda.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xiongda.mapper.AuditLogMapper;
import com.xiongda.model.entity.AuditLog;
import com.xiongda.model.vo.AuditLogVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuditLogServiceImpl 单元测试 — 覆盖 recordLog 落库字段与查询筛选/分页。
 *
 * <p>纯 Mockito 单元测试，mock AuditLogMapper，不依赖数据库。
 */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceImplTest {

    @Mock
    private AuditLogMapper auditLogMapper;

    @InjectMocks
    private AuditLogServiceImpl auditLogService;

    @BeforeEach
    void setUp() {
        // ServiceImpl 的 baseMapper 由 Spring 启动时注入，单测需手动设置
        ReflectionTestUtils.setField(auditLogService, "baseMapper", auditLogMapper);
    }

    @Test
    void recordLog_storesAllFieldsIncludingUserAgent() {
        auditLogService.recordLog(1L, 2L, "a@b.com", "login", "user",
                null, "{\"email\":\"a@b.com\"}", "127.0.0.1", "Mozilla/5.0");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        AuditLog saved = captor.getValue();
        assertEquals("login", saved.getAction());
        assertEquals("user", saved.getResourceType());
        assertEquals("a@b.com", saved.getUserEmail());
        assertEquals("127.0.0.1", saved.getIpAddress());
        assertEquals("Mozilla/5.0", saved.getUserAgent());
    }

    @Test
    void listLogsByTenant_filtersAndMapsToVO() {
        AuditLog log = new AuditLog();
        log.setId(10L);
        log.setAction("doc_upload");
        log.setUserEmail("a@b.com");
        Page<AuditLog> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(log));
        when(auditLogMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(page);

        Page<AuditLogVO> result = auditLogService.listLogsByTenant(
                1L, "doc_upload", null, null, null, 1, 10);

        assertEquals(1, result.getTotal());
        assertEquals("doc_upload", result.getRecords().get(0).getAction());
        assertEquals("a@b.com", result.getRecords().get(0).getUserEmail());
        verify(auditLogMapper).selectPage(any(Page.class), any(QueryWrapper.class));
    }

    @Test
    void listAllLogs_ignoresTenant() {
        Page<AuditLog> page = new Page<>(1, 10, 0);
        page.setRecords(List.of());
        when(auditLogMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(page);

        Page<AuditLogVO> result = auditLogService.listAllLogs(
                null, null, null, null, 1, 10);

        assertEquals(0, result.getTotal());
        verify(auditLogMapper).selectPage(any(Page.class), any(QueryWrapper.class));
    }
}
