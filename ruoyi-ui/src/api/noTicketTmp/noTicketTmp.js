import request from '@/utils/request'

// 查询入库通知单明细-未送审列表
export function listTmp(query) {
  return request({
    url: '/supplychain/tmp/list',
    method: 'get',
    params: query
  })
}

// 查询入库通知单明细-未送审详细
export function getTmp(id) {
  return request({
    url: '/supplychain/tmp/' + id,
    method: 'get'
  })
}

// 新增入库通知单明细-未送审
export function addTmp(data) {
  return request({
    url: '/supplychain/tmp',
    method: 'post',
    data: data
  })
}

// 修改入库通知单明细-未送审
export function updateTmp(data) {
  return request({
    url: '/supplychain/tmp',
    method: 'put',
    data: data
  })
}

// 删除入库通知单明细-未送审
export function delTmp(id) {
  return request({
    url: '/supplychain/tmp/' + id,
    method: 'delete'
  })
}
