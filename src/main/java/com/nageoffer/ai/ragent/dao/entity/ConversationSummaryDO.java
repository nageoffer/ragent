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
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * 会话摘要数据实体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Accessors(chain = true)
@TableName("t_conversation_summary")
public class ConversationSummaryDO {

    /**
     * 主键 ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 会话 ID
     */
    private String conversationId;

    /**
     * 用户 ID
     */
    private String userId;

    /**
     * 摘要内容
     */
    private String content;

    /**
     * 摘要覆盖的最后一条消息 ID
     */
    private Long lastMessageId;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    /**
     * 删除标志(0-未删除,1-已删除)
     */
    @TableLogic
    private Integer deleted;
}
