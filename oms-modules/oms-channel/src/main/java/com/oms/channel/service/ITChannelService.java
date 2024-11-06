package com.oms.channel.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.oms.channel.model.entity.TChannel;
import java.util.List;


/**
 * 店铺信息Service接口
 *
 * @author ruoyi
 * @date 2024-11-06
 */
public interface ITChannelService extends IService<TChannel>
{
    /**
     * 查询店铺信息
     *
     * @param channelId 店铺信息主键
     * @return 店铺信息
     */
    public TChannel selectTChannelByChannelId(Integer channelId);

    /**
     * 查询店铺信息列表
     *
     * @param tChannel 店铺信息
     * @return 店铺信息集合
     */
    public List<TChannel> selectTChannelList(TChannel tChannel);

    /**
     * 新增店铺信息
     *
     * @param tChannel 店铺信息
     * @return 结果
     */
    public int insertTChannel(TChannel tChannel);

    /**
     * 修改店铺信息
     *
     * @param tChannel 店铺信息
     * @return 结果
     */
    public int updateTChannel(TChannel tChannel);

    /**
     * 批量删除店铺信息
     *
     * @param channelIds 需要删除的店铺信息主键集合
     * @return 结果
     */
    public int deleteTChannelByChannelIds(Integer[] channelIds);

    /**
     * 删除店铺信息信息
     *
     * @param channelId 店铺信息主键
     * @return 结果
     */
    public int deleteTChannelByChannelId(Integer channelId);
}

