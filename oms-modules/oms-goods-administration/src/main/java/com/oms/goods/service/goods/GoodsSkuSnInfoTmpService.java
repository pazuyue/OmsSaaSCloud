package com.oms.goods.service.goods;

import com.baomidou.mybatisplus.extension.service.IService;
import com.oms.goods.model.entity.goods.GoodsSkuSnInfoTmp;
import com.oms.goods.model.vo.export.GoodsVO;

import java.util.List;

public interface GoodsSkuSnInfoTmpService extends IService<GoodsSkuSnInfoTmp> {
    /**
     * 导入
     * @param list
     * @return
     */
    public boolean export(List<GoodsVO> list);

    /**
     * 重新导入
     * @param list
     * @param importBatch
     * @return
     */
    public boolean export(List<GoodsVO> list,String importBatch);

    /**
     * 获取列表
     * @param importBatch
     * @return
     */
    public List<GoodsSkuSnInfoTmp> exportList(String importBatch);



}
