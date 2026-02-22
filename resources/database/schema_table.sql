CREATE
DATABASE IF NOT EXISTS `ragent`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

CREATE TABLE `t_conversation`
(
    `id`              bigint(20) NOT NULL COMMENT '主键ID',
    `conversation_id` varchar(64)  NOT NULL COMMENT '会话ID',
    `user_id`         varchar(64)  NOT NULL COMMENT '用户ID',
    `title`           varchar(128) NOT NULL COMMENT '会话名称',
    `last_time`       datetime DEFAULT NULL COMMENT '最近消息时间',
    `create_time`     datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime DEFAULT NULL COMMENT '更新时间',
    `deleted`         tinyint(4) DEFAULT '0' COMMENT '是否删除 0：正常 1：删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conversation_user` (`conversation_id`,`user_id`),
    KEY               `idx_user_time` (`user_id`,`last_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话列表';

CREATE TABLE `t_conversation_summary`
(
    `id`              bigint(20) NOT NULL COMMENT '主键ID',
    `conversation_id` varchar(64) NOT NULL COMMENT '会话ID',
    `user_id`         varchar(64) NOT NULL COMMENT '用户ID',
    `last_message_id` varchar(64) NOT NULL COMMENT '摘要最后消息ID',
    `content`         text        NOT NULL COMMENT '会话摘要内容',
    `create_time`     datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime DEFAULT NULL COMMENT '更新时间',
    `deleted`         tinyint(4) DEFAULT '0' COMMENT '是否删除 0：正常 1：删除',
    PRIMARY KEY (`id`),
    KEY               `idx_conv_user` (`conversation_id`,`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话摘要表（与消息表分离存储）';

CREATE TABLE `t_ingestion_pipeline`
(
    `id`          bigint(20) NOT NULL COMMENT 'ID',
    `name`        varchar(100) NOT NULL COMMENT '流水线名称',
    `description` text COMMENT '流水线描述',
    `created_by`  varchar(64)           DEFAULT '' COMMENT '创建人',
    `updated_by`  varchar(64)           DEFAULT '' COMMENT '更新人',
    `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否删除 0：正常 1：删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ingestion_pipeline_name` (`name`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='摄取流水线定义';

CREATE TABLE `t_ingestion_pipeline_node`
(
    `id`             bigint(20) NOT NULL COMMENT 'ID',
    `pipeline_id`    bigint(20) NOT NULL COMMENT '流水线ID',
    `node_id`        varchar(64) NOT NULL COMMENT '节点标识(同一流水线内唯一)',
    `node_type`      varchar(30) NOT NULL COMMENT '节点类型',
    `next_node_id`   varchar(64)          DEFAULT NULL COMMENT '下一个节点ID',
    `settings_json`  json                 DEFAULT NULL COMMENT '节点配置JSON',
    `condition_json` json                 DEFAULT NULL COMMENT '条件JSON',
    `created_by`     varchar(64)          DEFAULT '' COMMENT '创建人',
    `updated_by`     varchar(64)          DEFAULT '' COMMENT '更新人',
    `create_time`    datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否删除 0：正常 1：删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ingestion_pipeline_node` (`pipeline_id`,`node_id`,`deleted`),
    KEY              `idx_ingestion_pipeline_node_pipeline` (`pipeline_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='摄取流水线节点配置';

CREATE TABLE `t_ingestion_task`
(
    `id`               bigint(20) NOT NULL COMMENT 'ID',
    `pipeline_id`      bigint(20) NOT NULL COMMENT '流水线ID',
    `source_type`      varchar(20) NOT NULL COMMENT '来源类型',
    `source_location`  text COMMENT '来源地址或URL',
    `source_file_name` varchar(255)         DEFAULT NULL COMMENT '原始文件名',
    `status`           varchar(20) NOT NULL COMMENT '任务状态',
    `chunk_count`      int(11) DEFAULT '0' COMMENT '分块数量',
    `error_message`    text COMMENT '错误信息',
    `logs_json`        json                 DEFAULT NULL COMMENT '节点日志JSON',
    `metadata_json`    json                 DEFAULT NULL COMMENT '扩展元数据JSON',
    `started_at`       datetime             DEFAULT NULL COMMENT '开始时间',
    `completed_at`     datetime             DEFAULT NULL COMMENT '完成时间',
    `created_by`       varchar(64)          DEFAULT '' COMMENT '创建人',
    `updated_by`       varchar(64)          DEFAULT '' COMMENT '更新人',
    `create_time`      datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否删除 0：正常 1：删除',
    PRIMARY KEY (`id`),
    KEY                `idx_ingestion_task_pipeline` (`pipeline_id`),
    KEY                `idx_ingestion_task_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='摄取任务记录';

CREATE TABLE `t_ingestion_task_node`
(
    `id`            bigint(20) NOT NULL COMMENT 'ID',
    `task_id`       bigint(20) NOT NULL COMMENT '任务ID',
    `pipeline_id`   bigint(20) NOT NULL COMMENT '流水线ID',
    `node_id`       varchar(64) NOT NULL COMMENT '节点标识',
    `node_type`     varchar(30) NOT NULL COMMENT '节点类型',
    `node_order`    int(11) NOT NULL DEFAULT '0' COMMENT '节点顺序',
    `status`        varchar(20) NOT NULL COMMENT '节点状态',
    `duration_ms`   bigint(20) NOT NULL DEFAULT '0' COMMENT '执行耗时(毫秒)',
    `message`       text COMMENT '节点消息',
    `error_message` text COMMENT '错误信息',
    `output_json`   longtext COMMENT '节点输出JSON(全量)',
    `create_time`   datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否删除 0：正常 1：删除',
    PRIMARY KEY (`id`),
    KEY             `idx_ingestion_task_node_task` (`task_id`),
    KEY             `idx_ingestion_task_node_pipeline` (`pipeline_id`),
    KEY             `idx_ingestion_task_node_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='摄取任务节点执行记录';

CREATE TABLE `t_intent_node`
(
    `id`                    bigint(20) NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `kb_id`                 bigint(20) DEFAULT NULL COMMENT '知识库ID',
    `intent_code`           varchar(64) NOT NULL COMMENT '业务唯一标识',
    `name`                  varchar(64) NOT NULL COMMENT '展示名称',
    `level`                 tinyint(4) NOT NULL COMMENT '层级 0：DOMAIN 1：CATEGORY 2：TOPIC',
    `parent_code`           varchar(64)          DEFAULT NULL COMMENT '父节点标识',
    `description`           varchar(512)         DEFAULT NULL COMMENT '语义描述',
    `examples`              text COMMENT '示例问题',
    `collection_name`       varchar(128)         DEFAULT NULL COMMENT '关联的Collection名称',
    `top_k`                 int(11) DEFAULT NULL COMMENT '知识库检索TopK',
    `mcp_tool_id`           varchar(128)         DEFAULT NULL COMMENT 'MCP工具ID',
    `kind`                  tinyint(1) NOT NULL DEFAULT '0' COMMENT '类型 0：RAG知识库类 1：SYSTEM系统交互类',
    `prompt_snippet`        text COMMENT '提示词片段',
    `prompt_template`       text COMMENT '提示词模板',
    `param_prompt_template` text COMMENT '参数提取提示词模板（MCP模式专属）',
    `sort_order`            int(11) NOT NULL DEFAULT '0' COMMENT '排序字段',
    `enabled`               tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用 1：启用 0：禁用',
    `create_by`             varchar(64)          DEFAULT NULL COMMENT '创建人',
    `update_by`             varchar(64)          DEFAULT NULL COMMENT '修改人',
    `create_time`           datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`           datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `deleted`               tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否删除 0：正常 1：删除',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=2018166720850104321 DEFAULT CHARSET=utf8mb4 COMMENT='RAG意图树节点配置表';

CREATE TABLE `t_knowledge_base`
(
    `id`              bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `name`            varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '知识库名称',
    `embedding_model` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '嵌入模型标识',
    `collection_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Milvus Collection',
    `created_by`      varchar(64) COLLATE utf8mb4_unicode_ci  NOT NULL COMMENT '创建人',
    `updated_by`      varchar(64) COLLATE utf8mb4_unicode_ci           DEFAULT NULL COMMENT '修改人',
    `create_time`     datetime                                NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     datetime                                NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`         tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否删除 0：正常 1：删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_collection_name` (`collection_name`) COMMENT 'Collection 唯一约束',
    KEY               `idx_kb_name` (`name`)
) ENGINE=InnoDB AUTO_INCREMENT=2018586042835763201 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG知识库表';

CREATE TABLE `t_knowledge_chunk`
(
    `id`           bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `kb_id`        bigint(20) NOT NULL COMMENT '知识库ID',
    `doc_id`       bigint(20) NOT NULL COMMENT '文档ID',
    `chunk_index`  int(11) NOT NULL COMMENT '分块序号（从0开始）',
    `content`      longtext COLLATE utf8mb4_unicode_ci    NOT NULL COMMENT '分块正文内容',
    `content_hash` varchar(64) COLLATE utf8mb4_unicode_ci          DEFAULT NULL COMMENT '内容哈希（用于幂等/去重）',
    `char_count`   int(11) DEFAULT NULL COMMENT '字符数（可用于统计/调参）',
    `token_count`  int(11) DEFAULT NULL COMMENT 'Token数（可选）',
    `enabled`      tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用 0：禁用 1：启用',
    `created_by`   varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '创建人',
    `updated_by`   varchar(64) COLLATE utf8mb4_unicode_ci          DEFAULT NULL COMMENT '修改人',
    `create_time`  datetime                               NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  datetime                               NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`      tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否删除 0：正常 1：删除',
    PRIMARY KEY (`id`),
    KEY            `idx_doc_id` (`doc_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2019941971888291843 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG知识库文档分块表';

CREATE TABLE `t_knowledge_document`
(
    `id`               bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `kb_id`            bigint(20) NOT NULL COMMENT '知识库ID',
    `doc_name`         varchar(256) COLLATE utf8mb4_unicode_ci  NOT NULL COMMENT '文档名称',
    `enabled`          tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用 1：启用 0：禁用',
    `chunk_count`      int(11) DEFAULT '0' COMMENT '分块数（chunk 数量）',
    `file_url`         varchar(1024) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文件地址',
    `file_type`        varchar(32) COLLATE utf8mb4_unicode_ci   NOT NULL COMMENT '文件类型',
    `file_size`        bigint(20) DEFAULT NULL COMMENT '文件大小（单位字节）',
    `process_mode`     varchar(32) COLLATE utf8mb4_unicode_ci            DEFAULT 'chunk' COMMENT '处理模式',
    `status`           varchar(32) COLLATE utf8mb4_unicode_ci   NOT NULL DEFAULT 'pending' COMMENT '状态',
    `source_type`      varchar(32) COLLATE utf8mb4_unicode_ci            DEFAULT NULL COMMENT '来源类型：file/url',
    `source_location`  varchar(1024) COLLATE utf8mb4_unicode_ci          DEFAULT NULL COMMENT '来源位置（URL）',
    `schedule_enabled` tinyint(1) DEFAULT NULL COMMENT '定时拉取 0：否 1：是',
    `schedule_cron`    varchar(128) COLLATE utf8mb4_unicode_ci           DEFAULT NULL COMMENT '定时拉取cron表达式',
    `chunk_strategy`   varchar(32) COLLATE utf8mb4_unicode_ci            DEFAULT NULL COMMENT '分块策略',
    `chunk_config`     json                                              DEFAULT NULL COMMENT '分块参数JSON',
    `pipeline_id`      bigint(20) DEFAULT NULL COMMENT '数据通道ID',
    `created_by`       varchar(64) COLLATE utf8mb4_unicode_ci   NOT NULL COMMENT '创建人',
    `updated_by`       varchar(64) COLLATE utf8mb4_unicode_ci            DEFAULT NULL COMMENT '修改人',
    `create_time`      datetime                                 NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      datetime                                 NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否删除 0：正常 1：删除',
    PRIMARY KEY (`id`),
    KEY                `idx_kb_id` (`kb_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2019941957409554433 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG知识库文档表';

CREATE TABLE `t_knowledge_document_chunk_log`
(
    `id`                 bigint(20) NOT NULL COMMENT '主键ID',
    `doc_id`             bigint(20) NOT NULL COMMENT '文档ID',
    `status`             varchar(20) NOT NULL COMMENT '执行状态',
    `process_mode`       varchar(20) DEFAULT NULL COMMENT '处理模式',
    `chunk_strategy`     varchar(50) DEFAULT NULL COMMENT '分块策略（仅chunk模式）',
    `pipeline_id`        bigint(20) DEFAULT NULL COMMENT 'Pipeline ID（仅pipeline模式）',
    `extract_duration`   bigint(20) DEFAULT NULL COMMENT '文本提取耗时（毫秒）',
    `chunk_duration`     bigint(20) DEFAULT NULL COMMENT '分块耗时（毫秒）',
    `embedding_duration` bigint(20) DEFAULT NULL COMMENT '向量化耗时（毫秒）',
    `total_duration`     bigint(20) DEFAULT NULL COMMENT '总耗时（毫秒）',
    `chunk_count`        int(11) DEFAULT NULL COMMENT '生成的分块数量',
    `error_message`      text COMMENT '错误信息',
    `start_time`         datetime    DEFAULT NULL COMMENT '开始时间',
    `end_time`           datetime    DEFAULT NULL COMMENT '结束时间',
    `create_time`        datetime    DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`        datetime    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY                  `idx_doc_id` (`doc_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库文档分块日志表';

CREATE TABLE `t_knowledge_document_schedule`
(
    `id`                bigint(20) NOT NULL COMMENT '主键ID',
    `doc_id`            bigint(20) NOT NULL COMMENT '文档ID',
    `kb_id`             bigint(20) NOT NULL COMMENT '知识库ID',
    `cron_expr`         varchar(128)      DEFAULT NULL COMMENT '定时表达式',
    `enabled`           tinyint(4) DEFAULT '0' COMMENT '是否启用定时',
    `next_run_time`     datetime          DEFAULT NULL COMMENT '下一次执行时间',
    `last_run_time`     datetime          DEFAULT NULL COMMENT '上一次执行时间',
    `last_success_time` datetime          DEFAULT NULL COMMENT '上一次成功时间',
    `last_status`       varchar(32)       DEFAULT NULL COMMENT '上一次执行状态',
    `last_error`        varchar(512)      DEFAULT NULL COMMENT '上一次执行错误',
    `last_etag`         varchar(256)      DEFAULT NULL COMMENT '上一次ETag',
    `last_modified`     varchar(256)      DEFAULT NULL COMMENT '上一次Last-Modified',
    `last_content_hash` varchar(128)      DEFAULT NULL COMMENT '上一次内容哈希',
    `lock_owner`        varchar(128)      DEFAULT NULL COMMENT '锁持有者',
    `lock_until`        datetime          DEFAULT NULL COMMENT '锁到期时间',
    `create_time`       datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_doc_id` (`doc_id`),
    KEY                 `idx_next_run` (`next_run_time`),
    KEY                 `idx_lock_until` (`lock_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库文档定时刷新任务表';

CREATE TABLE `t_knowledge_document_schedule_exec`
(
    `id`            bigint(20) NOT NULL COMMENT '主键ID',
    `schedule_id`   bigint(20) NOT NULL COMMENT '定时任务ID',
    `doc_id`        bigint(20) NOT NULL COMMENT '文档ID',
    `kb_id`         bigint(20) NOT NULL COMMENT '知识库ID',
    `status`        varchar(32) NOT NULL COMMENT '执行状态',
    `message`       varchar(512)         DEFAULT NULL COMMENT '执行信息',
    `start_time`    datetime             DEFAULT NULL COMMENT '开始时间',
    `end_time`      datetime             DEFAULT NULL COMMENT '结束时间',
    `file_name`     varchar(512)         DEFAULT NULL COMMENT '文件名',
    `file_size`     bigint(20) DEFAULT NULL COMMENT '文件大小',
    `content_hash`  varchar(128)         DEFAULT NULL COMMENT '内容哈希',
    `etag`          varchar(256)         DEFAULT NULL COMMENT 'ETag',
    `last_modified` varchar(256)         DEFAULT NULL COMMENT 'Last-Modified',
    `create_time`   datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY             `idx_schedule_time` (`schedule_id`,`start_time`),
    KEY             `idx_doc_id` (`doc_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库文档定时刷新执行记录';

CREATE TABLE `t_message`
(
    `id`              bigint(20) NOT NULL COMMENT '主键ID',
    `conversation_id` varchar(64) NOT NULL COMMENT '会话ID',
    `user_id`         varchar(64) NOT NULL COMMENT '用户ID',
    `role`            varchar(32) NOT NULL COMMENT '角色：system/user/assistant',
    `content`         text        NOT NULL COMMENT '消息内容',
    `create_time`     datetime DEFAULT NULL COMMENT '创建时间',
    `update_time`     datetime DEFAULT NULL COMMENT '更新时间',
    `deleted`         tinyint(4) DEFAULT '0' COMMENT '是否删除 0：正常 1：删除',
    PRIMARY KEY (`id`),
    KEY               `idx_conversation_user_time` (`conversation_id`,`user_id`,`create_time`),
    KEY               `idx_conversation_summary` (`conversation_id`,`user_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话消息记录表';

CREATE TABLE `t_message_feedback`
(
    `id`              bigint(20) NOT NULL COMMENT '主键ID',
    `message_id`      bigint(20) NOT NULL COMMENT '关联的消息ID',
    `conversation_id` varchar(64) NOT NULL COMMENT '会话ID',
    `user_id`         varchar(64) NOT NULL COMMENT '用户ID',
    `vote`            tinyint(1) NOT NULL COMMENT '反馈值 1：点赞 -1：点踩',
    `reason`          varchar(255)  DEFAULT NULL COMMENT '反馈原因',
    `comment`         varchar(1024) DEFAULT NULL COMMENT '补充说明',
    `create_time`     datetime    NOT NULL COMMENT '创建时间',
    `update_time`     datetime    NOT NULL COMMENT '更新时间',
    `deleted`         tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否删除 0：正常 1：删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_msg_user` (`message_id`,`user_id`),
    KEY               `idx_conversation_id` (`conversation_id`),
    KEY               `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话消息反馈表';

CREATE TABLE `t_query_term_mapping`
(
    `id`          bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
    `domain`      varchar(64)           DEFAULT NULL COMMENT '业务域/系统标识，如biz、group、data_security等，可选',
    `source_term` varchar(128) NOT NULL COMMENT '用户常说的原始词',
    `target_term` varchar(128) NOT NULL COMMENT '归一化后的目标词',
    `match_type`  tinyint(4) NOT NULL DEFAULT '1' COMMENT '匹配类型 1：精确匹配 2：前缀匹配 3：正则匹配 4：整词匹配',
    `priority`    int(11) NOT NULL DEFAULT '100' COMMENT '优先级，数值越小优先级越高（先匹配长词）',
    `enabled`     tinyint(4) NOT NULL DEFAULT '1' COMMENT '是否生效 1：生效 0：禁用',
    `remark`      varchar(255)          DEFAULT NULL COMMENT '备注',
    `create_by`   varchar(64)           DEFAULT NULL COMMENT '创建人',
    `update_by`   varchar(64)           DEFAULT NULL COMMENT '修改人',
    `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `deleted`     tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否删除 0：正常 1：删除',
    PRIMARY KEY (`id`),
    KEY           `idx_domain` (`domain`),
    KEY           `idx_source` (`source_term`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG关键词归一化映射表';

CREATE TABLE `t_rag_trace_node`
(
    `id`             bigint(20) NOT NULL COMMENT 'ID',
    `trace_id`       varchar(64) NOT NULL COMMENT '所属链路ID',
    `node_id`        varchar(64) NOT NULL COMMENT '节点ID',
    `parent_node_id` varchar(64)          DEFAULT NULL COMMENT '父节点ID',
    `depth`          int(11) DEFAULT '0' COMMENT '节点深度',
    `node_type`      varchar(64)          DEFAULT NULL COMMENT '节点类型',
    `node_name`      varchar(128)         DEFAULT NULL COMMENT '节点名称',
    `class_name`     varchar(256)         DEFAULT NULL COMMENT '类名',
    `method_name`    varchar(128)         DEFAULT NULL COMMENT '方法名',
    `status`         varchar(16) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/SUCCESS/ERROR',
    `error_message`  varchar(1000)        DEFAULT NULL COMMENT '错误信息',
    `start_time`     datetime(3) DEFAULT NULL COMMENT '开始时间',
    `end_time`       datetime(3) DEFAULT NULL COMMENT '结束时间',
    `duration_ms`    bigint(20) DEFAULT NULL COMMENT '耗时毫秒',
    `extra_data`     text COMMENT '扩展字段(JSON)',
    `create_time`    datetime             DEFAULT CURRENT_TIMESTAMP,
    `update_time`    datetime             DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`        tinyint(4) DEFAULT '0',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_run_node` (`trace_id`,`node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG Trace 节点记录表';

CREATE TABLE `t_rag_trace_run`
(
    `id`              bigint(20) NOT NULL COMMENT 'ID',
    `trace_id`        varchar(64) NOT NULL COMMENT '全局链路ID',
    `trace_name`      varchar(128)         DEFAULT NULL COMMENT '链路名称',
    `entry_method`    varchar(256)         DEFAULT NULL COMMENT '入口方法',
    `conversation_id` varchar(64)          DEFAULT NULL COMMENT '会话ID',
    `task_id`         varchar(64)          DEFAULT NULL COMMENT '任务ID',
    `user_id`         varchar(64)          DEFAULT NULL COMMENT '用户ID',
    `status`          varchar(16) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/SUCCESS/ERROR',
    `error_message`   varchar(1000)        DEFAULT NULL COMMENT '错误信息',
    `start_time`      datetime(3) DEFAULT NULL COMMENT '开始时间',
    `end_time`        datetime(3) DEFAULT NULL COMMENT '结束时间',
    `duration_ms`     bigint(20) DEFAULT NULL COMMENT '耗时毫秒',
    `extra_data`      text COMMENT '扩展字段(JSON)',
    `create_time`     datetime             DEFAULT CURRENT_TIMESTAMP,
    `update_time`     datetime             DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`         tinyint(4) DEFAULT '0',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_run_id` (`trace_id`),
    KEY               `idx_task_id` (`task_id`),
    KEY               `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG Trace 运行记录表';

CREATE TABLE `t_sample_question`
(
    `id`          bigint(20) NOT NULL COMMENT 'ID',
    `title`       varchar(64)  DEFAULT NULL COMMENT '展示标题',
    `description` varchar(255) DEFAULT NULL COMMENT '描述或提示',
    `question`    varchar(1024) NOT NULL COMMENT '示例问题内容',
    `create_time` datetime     DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime     DEFAULT NULL COMMENT '更新时间',
    `deleted`     tinyint(4) DEFAULT '0' COMMENT '是否删除 0：正常 1：删除',
    PRIMARY KEY (`id`),
    KEY           `idx_sample_question_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='示例问题表';

CREATE TABLE `t_user`
(
    `id`          bigint(20) NOT NULL COMMENT '主键ID',
    `username`    varchar(64)  NOT NULL COMMENT '用户名，唯一',
    `password`    varchar(128) NOT NULL COMMENT '密码',
    `role`        varchar(32)  NOT NULL COMMENT '角色：admin/user',
    `avatar`      varchar(128) DEFAULT NULL COMMENT '用户头像',
    `create_time` datetime     DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime     DEFAULT NULL COMMENT '更新时间',
    `deleted`     tinyint(4) DEFAULT '0' COMMENT '是否删除 0：正常 1：删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';