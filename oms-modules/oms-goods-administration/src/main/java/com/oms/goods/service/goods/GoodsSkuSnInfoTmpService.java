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
    public String export(List<GoodsVO> list,String companyCode);

    /**
     * 重新导入
     * @param list
     * @param importBatch
     * @return
     */
    public String export(List<GoodsVO> list,String importBatch,String companyCode);

    /**
     * 获取列表
     * @param importBatch
     * @return
     */
    public List<GoodsSkuSnInfoTmp> exportList(String importBatch);



}
