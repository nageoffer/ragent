package com.nageoffer.ai.ragent.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 会话消息实体类
 * 用于存储对话过程中的消息记录
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_conversation_message")
public class ConversationMessageDO {

    /**
     * 主键 ID，采用雪花算法生成
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 会话 ID，关联到具体的对话会话
     */
    private String conversationId;

    /**
     * 用户 ID，标识消息发送者
     */
    private String userId;

    /**
     * 角色：user/assistant
     * user: 用户消息
     * assistant: 助手回复
     */
    private String role;

    /**
     * 消息内容，存储实际的消息文本
     */
    private String content;

    /**
     * 创建时间，自动填充
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    /**
     * 更新时间，插入和更新时自动填充
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    /**
     * 删除标识，逻辑删除字段
     */
    @TableLogic
    private Integer deleted;
}
