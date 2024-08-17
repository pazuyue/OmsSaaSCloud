package com.oms.goods.model.entity.goods;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ruoyi.common.core.annotation.Excel;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * <p>
 * 商品颜色表
 * </p>
 *
 * @author 月光光
 * @since 2023-07-11
 */
@TableName("goods_color")
@Data
public class GoodsColor implements Serializable {

    private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 颜色
     */
    @Excel(name = "颜色名称")
    private String colorName;

    /**
     * 外部颜色编码
     */
    @Excel(name = "颜色编码")
    private String outColorCode;

    /**
     * 公司code
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
