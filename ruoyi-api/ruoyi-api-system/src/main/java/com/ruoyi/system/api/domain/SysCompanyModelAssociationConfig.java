package com.ruoyi.system.api.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.core.annotation.Excel;
import com.ruoyi.common.core.web.domain.BaseEntity;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import java.util.Date;

/**
 * 公司-OMS系统插件关联对象 sys_company_model_association_config
 *
 * @author ruoyi
 * @date 2024-07-26
 */
public class SysCompanyModelAssociationConfig extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** ID */
    private Integer id;

    /** 公司编码 */
    @Excel(name = "公司编码")
    private String companyCode;

    /** 订单转单模式 1 默认模型 2 QM模型 3 ECCO 模型 */
    @Excel(name = "订单转单模式 1 默认模型 2 QM模型 3 ECCO 模型")
    private Long orderTransferModel;

    /** 商品处理模式 1默认 2QM */
    @Excel(name = "商品处理模式 1默认 2QM")
    private Long goodsHandleModel;

    /** 修改时间 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "修改时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date modifyTime;

    public void setId(Integer id)
    {
        this.id = id;
    }

    public Integer getId()
    {
        return id;
    }
    public void setCompanyCode(String companyCode)
    {
        this.companyCode = companyCode;
    }

    public String getCompanyCode()
    {
        return companyCode;
    }
    public void setOrderTransferModel(Long orderTransferModel)
    {
        this.orderTransferModel = orderTransferModel;
    }

    public Long getOrderTransferModel()
    {
        return orderTransferModel;
    }
    public void setGoodsHandleModel(Long goodsHandleModel)
    {
        this.goodsHandleModel = goodsHandleModel;
    }

    public Long getGoodsHandleModel()
    {
        return goodsHandleModel;
    }
    public void setModifyTime(Date modifyTime)
    {
        this.modifyTime = modifyTime;
    }

    public Date getModifyTime()
    {
        return modifyTime;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
                .append("id", getId())
                .append("companyCode", getCompanyCode())
                .append("orderTransferModel", getOrderTransferModel())
                .append("goodsHandleModel", getGoodsHandleModel())
                .append("createTime", getCreateTime())
                .append("modifyTime", getModifyTime())
                .toString();
    }
}

