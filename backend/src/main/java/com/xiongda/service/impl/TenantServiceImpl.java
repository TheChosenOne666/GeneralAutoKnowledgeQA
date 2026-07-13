package com.xiongda.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiongda.common.ErrorCode;
import com.xiongda.constant.UserConstant;
import com.xiongda.exception.ThrowUtils;
import com.xiongda.mapper.DocumentMapper;
import com.xiongda.mapper.TenantMapper;
import com.xiongda.mapper.UserMapper;
import com.xiongda.model.dto.tenant.TenantCreateRequest;
import com.xiongda.model.dto.tenant.TenantQuotaRequest;
import com.xiongda.model.entity.Document;
import com.xiongda.model.entity.Tenant;
import com.xiongda.model.entity.User;
import com.xiongda.model.vo.TenantVO;
import com.xiongda.service.TenantService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/**
 * 租户服务实现 — 平台超管管理所有租户。
 *
 * @author <a href="https://github.com/TheChosenOne666">小楼</a>
 */
@Service
public class TenantServiceImpl extends ServiceImpl<TenantMapper, Tenant> implements TenantService {

    private static final List<String> VALID_STATUS = Arrays.asList("active", "suspended");

    @Resource
    private UserMapper userMapper;

    @Resource
    private DocumentMapper documentMapper;

    @Override
    public Page<TenantVO> listTenants(long current, long pageSize) {
        Page<Tenant> page = this.page(new Page<>(current, pageSize));
        Page<TenantVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVO).toList());
        return voPage;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TenantVO createTenant(TenantCreateRequest request) {
        // slug 唯一校验
        QueryWrapper<Tenant> slugQw = new QueryWrapper<>();
        slugQw.eq("slug", request.getSlug());
        ThrowUtils.throwIf(this.count(slugQw) > 0, ErrorCode.PARAMS_ERROR, "租户标识已存在");

        // 管理员邮箱对应的用户必须已存在（不凭空创建账号，对齐 WeKnora）
        QueryWrapper<User> userQw = new QueryWrapper<>();
        userQw.eq("email", request.getAdminEmail());
        User admin = userMapper.selectOne(userQw);
        ThrowUtils.throwIf(admin == null, ErrorCode.PARAMS_ERROR, "管理员邮箱对应的用户不存在，请先注册");
        ThrowUtils.throwIf(UserConstant.SUPER_ADMIN_ROLE.equals(admin.getRole()),
                ErrorCode.PARAMS_ERROR, "不能将平台超管设置为租户管理员");

        // 创建租户
        Tenant tenant = new Tenant();
        tenant.setName(request.getName());
        tenant.setSlug(request.getSlug());
        tenant.setStatus("active");
        tenant.setMaxMembers(request.getMaxMembers() != null ? request.getMaxMembers() : 50);
        tenant.setMaxDocuments(request.getMaxDocuments() != null ? request.getMaxDocuments() : 1000);
        this.save(tenant);

        // 将已存在用户设为该租户首个管理员（类似 WeKnora EnsureOwner）
        admin.setTenantId(tenant.getId());
        admin.setRole(UserConstant.TENANT_ADMIN_ROLE);
        userMapper.updateById(admin);

        return toVO(tenant);
    }

    @Override
    public TenantVO setStatus(Long tenantId, String status) {
        ThrowUtils.throwIf(!VALID_STATUS.contains(status), ErrorCode.PARAMS_ERROR, "状态只能是 active 或 suspended");
        Tenant tenant = this.getById(tenantId);
        ThrowUtils.throwIf(tenant == null, ErrorCode.NOT_FOUND_ERROR, "租户不存在");
        tenant.setStatus(status);
        this.updateById(tenant);
        return toVO(tenant);
    }

    @Override
    public TenantVO setQuota(Long tenantId, TenantQuotaRequest request) {
        Tenant tenant = this.getById(tenantId);
        ThrowUtils.throwIf(tenant == null, ErrorCode.NOT_FOUND_ERROR, "租户不存在");
        if (request.getMaxMembers() != null) {
            ThrowUtils.throwIf(request.getMaxMembers() < 0, ErrorCode.PARAMS_ERROR, "成员上限不能为负");
            tenant.setMaxMembers(request.getMaxMembers());
        }
        if (request.getMaxDocuments() != null) {
            ThrowUtils.throwIf(request.getMaxDocuments() < 0, ErrorCode.PARAMS_ERROR, "文档上限不能为负");
            tenant.setMaxDocuments(request.getMaxDocuments());
        }
        this.updateById(tenant);
        return toVO(tenant);
    }

    /**
     * 转为 VO 并实时统计成员数 / 文档数（租户数量少，管理平台可接受）。
     */
    private TenantVO toVO(Tenant tenant) {
        TenantVO vo = new TenantVO();
        vo.setId(tenant.getId());
        vo.setName(tenant.getName());
        vo.setSlug(tenant.getSlug());
        vo.setStatus(tenant.getStatus());
        vo.setMaxMembers(tenant.getMaxMembers());
        vo.setMaxDocuments(tenant.getMaxDocuments());
        vo.setCreateTime(tenant.getCreateTime());
        QueryWrapper<User> uqw = new QueryWrapper<>();
        uqw.eq("tenant_id", tenant.getId());
        vo.setMemberCount(userMapper.selectCount(uqw));
        QueryWrapper<Document> dqw = new QueryWrapper<>();
        dqw.eq("tenant_id", tenant.getId());
        vo.setDocCount(documentMapper.selectCount(dqw));
        return vo;
    }
}
