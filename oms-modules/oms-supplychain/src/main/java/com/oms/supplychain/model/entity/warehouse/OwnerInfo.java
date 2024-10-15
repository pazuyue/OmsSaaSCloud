package com.oms.supplychain.model.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ruoyi.common.core.annotation.Excel;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * <p>
 * 货主基础信息表
 * </p>
 *
 * @author 月光光
 * @since 2023-07-18
 */
@TableName("owner_info")
@Data
public class OwnerInfo implements Serializable {

    private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 编主编码
     */
    @Excel(name = "货主编码")
    private String ownerCode;

    /**
     * 编主名称
     */
    @Excel(name = "货主名称")
    private String ownerName;

    /**
     * 1需同步商品资料给仓库，2不需同步商品资料给仓库
     */
    @Excel(name = "同步商品资料")
    private Byte isSync;

    /**
     * 2启用，1不启用
     */
    @Excel(name = "同步资料")
    private Byte isEnable;

    @Excel(name = "实仓编码")
    private String realStoreCode;

    /**
     * 公司编码
     */
    private String companyCode;

    /**
     * 添加时间
     */
    private Date createTime;

    /**
     * 修改时间
     */
    private Date modifyTime;
}
