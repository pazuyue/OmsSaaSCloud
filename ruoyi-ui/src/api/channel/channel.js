import request from '@/utils/request'

// 查询店铺信息列表
export function listChannel(query) {
  return request({
    url: '/channel/channel/list',
    method: 'get',
    params: query
  })
}

// 查询店铺信息详细
export function getChannel(channelId) {
  return request({
    url: '/channel/channel/' + channelId,
    method: 'get'
  })
}

// 新增店铺信息
export function addChannel(data) {
  return request({
    url: '/channel/channel',
    method: 'post',
    data: data
  })
}

// 修改店铺信息
export function updateChannel(data) {
  return request({
    url: '/channel/channel',
    method: 'put',
    data: data
  })
}

// 删除店铺信息
export function delChannel(channelId) {
  return request({
    url: '/channel/channel/' + channelId,
    method: 'delete'
  })
}
