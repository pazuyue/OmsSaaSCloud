package com.oms.goods.model.entity.goods;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * <p>
 * 商品类目表
 * </p>
 *
 * @author 月光光
 * @since 2023-06-25
 */
@TableName("goods_category")
@Data
public class GoodsCategory implements Serializable {

    private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 关联品牌
     */
    private String brandCode;

    /**
     * 公司code
     */
    private String companyCode;

    /**
     * 分类名称
     */
    private String name;

    /**
     * 父id
     */
    private Integer pid;

    /**
     * 添加时间
     */
    private Date createTime;

    /**
     * 修改时间
     */
    private Date modifyTime;

    /**
     * 1级别，2级别，3级别
     */
    private Byte level;

}
