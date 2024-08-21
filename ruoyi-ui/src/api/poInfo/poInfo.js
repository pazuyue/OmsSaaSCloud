import request from '@/utils/request'

// 查询采购单列表
export function listPoInfo(query) {
  return request({
    url: '/supplychain/poInfo/list',
    method: 'get',
    params: query
  })
}

// 查询采购单详细
export function getPoInfo(id) {
  return request({
    url: '/supplychain/poInfo/' + id,
    method: 'get'
  })
}

// 新增采购单
export function addPoInfo(data) {
  return request({
    url: '/supplychain/poInfo',
    method: 'post',
    data: data
  })
}

// 修改采购单
export function updatePoInfo(data) {
  return request({
    url: '/supplychain/poInfo',
    method: 'put',
    data: data
  })
}

// 删除采购单
export function delPoInfo(id) {
  return request({
    url: '/supplychain/poInfo/' + id,
    method: 'delete'
  })
}
