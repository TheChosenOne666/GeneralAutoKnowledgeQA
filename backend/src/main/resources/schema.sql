-- 熊答数据库建表脚本 — MyBatis-Plus 实体对应（bigint 雪花ID + 下划线列名 + 逻辑删除）
-- 重建：先删除旧表（JPA 遗留复数名），再按实体创建

-- 删除旧表
DROP TABLE IF EXISTS messages CASCADE;
DROP TABLE IF EXISTS conversations CASCADE;
DROP TABLE IF EXISTS documents CASCADE;
DROP TABLE IF EXISTS knowledge_bases CASCADE;
DROP TABLE IF EXISTS ai_configs CASCADE;
DROP TABLE IF EXISTS audit_logs CASCADE;
DROP TABLE IF EXISTS users CASCADE;
DROP TABLE IF EXISTS tenants CASCADE;

-- 租户表
CREATE TABLE tenant (
    id BIGINT PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    slug VARCHAR(100) NOT NULL UNIQUE,
    status VARCHAR(20) DEFAULT 'active',
    max_members INTEGER DEFAULT 50,
    max_documents INTEGER DEFAULT 1000,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_delete SMALLINT DEFAULT 0
);

-- 用户表（app_user 避开 PostgreSQL 保留字 user）
CREATE TABLE app_user (
    id BIGINT PRIMARY KEY,
    tenant_id BIGINT REFERENCES tenant(id),
    name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    user_password VARCHAR(255) NOT NULL,
    role VARCHAR(30) DEFAULT 'member',
    is_active SMALLINT DEFAULT 1,
    avatar_url VARCHAR(500),
    last_active_at TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_delete SMALLINT DEFAULT 0
);

-- AI 模型配置表
CREATE TABLE ai_config (
    id BIGINT PRIMARY KEY,
    tenant_id BIGINT,
    user_id BIGINT,
    llm_provider VARCHAR(50),
    llm_model VARCHAR(100),
    llm_api_key VARCHAR(500),
    llm_base_url VARCHAR(500),
    llm_temperature DOUBLE PRECISION,
    llm_max_tokens INTEGER,
    llm_models VARCHAR(1000),
    embedding_provider VARCHAR(50),
    embedding_model VARCHAR(100),
    embedding_api_key VARCHAR(500),
    embedding_base_url VARCHAR(500),
    embedding_dimension INTEGER,
    rerank_provider VARCHAR(50),
    rerank_model VARCHAR(100),
    rerank_api_key VARCHAR(500),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_delete SMALLINT DEFAULT 0
);

-- 审计日志表（无逻辑删除）
CREATE TABLE audit_log (
    id BIGINT PRIMARY KEY,
    tenant_id BIGINT,
    user_id BIGINT,
    user_email VARCHAR(255),
    action VARCHAR(50),
    resource_type VARCHAR(50),
    resource_id VARCHAR(100),
    detail TEXT,
    ip_address VARCHAR(50),
    user_agent VARCHAR(500),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 知识库表
CREATE TABLE knowledge_base (
    id BIGINT PRIMARY KEY,
    tenant_id BIGINT,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    scope VARCHAR(20) DEFAULT 'shared',
    owner_id BIGINT,
    document_count INTEGER DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_delete SMALLINT DEFAULT 0
);

-- 文档表
CREATE TABLE document (
    id BIGINT PRIMARY KEY,
    kb_id BIGINT,
    tenant_id BIGINT,
    filename VARCHAR(500) NOT NULL,
    file_type VARCHAR(20),
    file_size BIGINT,
    file_path VARCHAR(1000),
    status VARCHAR(20) DEFAULT 'pending',
    chunk_count INTEGER DEFAULT 0,
    error_msg TEXT,
    model_config_error BOOLEAN DEFAULT FALSE,
    uploaded_by BIGINT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_delete SMALLINT DEFAULT 0
);

-- 会话表
CREATE TABLE conversation (
    id BIGINT PRIMARY KEY,
    tenant_id BIGINT,
    user_id BIGINT,
    title VARCHAR(200),
    message_count INTEGER DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_delete SMALLINT DEFAULT 0
);

-- 消息表（无逻辑删除、无更新时间）
CREATE TABLE message (
    id BIGINT PRIMARY KEY,
    conversation_id BIGINT,
    role VARCHAR(20),
    content TEXT,
    sources TEXT,
    model VARCHAR(100),
    kb_ids TEXT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 租户邀请表（share-link 模式，可多人复用）
CREATE TABLE tenant_invitations (
    id BIGINT PRIMARY KEY,
    tenant_id BIGINT REFERENCES tenant(id),
    inviter_id BIGINT,
    invitee_name VARCHAR(100),
    invitee_email VARCHAR(255),
    role VARCHAR(30) DEFAULT 'member',
    token VARCHAR(100) NOT NULL UNIQUE,
    status VARCHAR(20) DEFAULT 'pending',
    accepted_count INTEGER DEFAULT 0,
    expires_at TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_delete SMALLINT DEFAULT 0
);

-- 索引
CREATE INDEX idx_user_tenant ON app_user(tenant_id);
CREATE INDEX idx_user_email ON app_user(email);
CREATE INDEX idx_kb_tenant ON knowledge_base(tenant_id);
CREATE INDEX idx_doc_kb ON document(kb_id);
CREATE INDEX idx_conv_user ON conversation(user_id);
CREATE INDEX idx_msg_conv ON message(conversation_id);
CREATE INDEX idx_audit_tenant ON audit_log(tenant_id);
CREATE INDEX idx_aiconfig_tenant ON ai_config(tenant_id);
