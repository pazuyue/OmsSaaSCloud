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
 * 商品尺码表
 * </p>
 *
 * @author 月光光
 * @since 2023-07-11
 */
@TableName("goods_size")
@Data
public class GoodsSize implements Serializable {

    private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 尺码名称
     */
    @Excel(name = "尺码名称")
    private String sizeName;

    /**
     * 公司code
     */
    private String companyCode;

    /**
     * 外部尺码编码
     */
    @Excel(name = "尺码编码")
    private String outSizeCode;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 修改时间
     */
    private Date modifyTime;
}
