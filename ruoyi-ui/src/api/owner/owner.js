import request from '@/utils/request'

// 查询货主基础信息列表
export function listInfo(query) {
  return request({
    url: '/supplychain/owner/list',
    method: 'get',
    params: query
  })
}

// 查询货主基础信息详细
export function getInfo(id) {
  return request({
    url: '/supplychain/owner/' + id,
    method: 'get'
  })
}

// 新增货主基础信息
export function addInfo(data) {
  return request({
    url: '/supplychain/owner',
    method: 'post',
    data: data
  })
}

// 修改货主基础信息
export function updateInfo(data) {
  return request({
    url: '/supplychain/owner',
    method: 'put',
    data: data
  })
}

// 删除货主基础信息
export function delInfo(id) {
  return request({
    url: '/supplychain/owner/' + id,
    method: 'delete'
  })
}
