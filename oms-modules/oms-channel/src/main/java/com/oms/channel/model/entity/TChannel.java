package com.oms.channel.model.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.core.annotation.Excel;
import com.ruoyi.common.core.web.domain.BaseEntity;

/**
 * 店铺信息对象 t_channel
 *
 * @author ruoyi
 * @date 2024-11-06
 */
@TableName("t_channel")
@Data
public class TChannel
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    @TableId(value = "channel_id", type = IdType.AUTO)
    private Integer channelId;

    /** 渠道名称 */
    @Excel(name = "渠道名称")
    private String channelName;

    /** 外部关联编码 */
    @Excel(name = "外部关联编码")
    private String outCorrelationCode;

    /** 渠道编码 */
    @Excel(name = "渠道编码")
    private String channelType;

    /** 是否启用 1-开启,0-禁用 */
    @Excel(name = "是否启用 1-开启,0-禁用")
    private Integer enabled;

    /** 对接平台, 1：需对接，0：无需对接 */
    @Excel(name = "对接平台, 1：需对接，0：无需对接")
    private Integer toChannelEnabled;

    /** 是否同步库存 1-开启, 0-关闭 */
    @Excel(name = "是否同步库存 1-开启, 0-关闭")
    private Integer syncEnabled;

    /** 经营模式：1直营, 0加盟 */
    @Excel(name = "经营模式：1直营, 0加盟")
    private Integer mModelType;

    /** 公司编码 */
    @Excel(name = "公司编码")
    private String companyCode;

    /** 修改时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "修改时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date modifyTime;
}
