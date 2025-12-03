package com.nageoffer.ai.ragent.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("t_query_term_mapping")
public class QueryTermMappingDO {

    private Long id;

    /**
     * 业务域/系统标识，如biz、group、data_security等，可选
     */
    private String domain;

    /**
     * 用户原始短语
     */
    private String sourceTerm;

    /**
     * 归一化后的目标短语
     */
    private String targetTerm;

    /**
     * 匹配类型 1：精确匹配 2：前缀匹配 3：正则匹配 4：整词匹配
     */
    private Integer matchType;

    /**
     * 优先级，数值越小优先级越高（一般长词在前）
     */
    private Integer priority;

    /**
     * 是否生效 1：生效 0：禁用
     */
    private Boolean enabled;

    /**
     * 备注
     */
    private String remark;

    private String createBy;

    private String updateBy;

    private Date createTime;

    private Date updateTime;
}
