import request from '@/utils/request'

// 查询产品信息列表
export function listInfo(query) {
  return request({
    url: '/goods/info/list',
    method: 'get',
    params: query
  })
}

// 查询产品信息详细
export function getInfo(id) {
  return request({
    url: '/goods/info/' + id,
    method: 'get'
  })
}

// 新增产品信息
export function addInfo(data) {
  return request({
    url: '/goods/info',
    method: 'post',
    data: data
  })
}

// 修改产品信息
export function updateInfo(data) {
  return request({
    url: '/goods/info',
    method: 'put',
    data: data
  })
}

// 删除产品信息
export function delInfo(id) {
  return request({
    url: '/goods/info/' + id,
    method: 'delete'
  })
}
