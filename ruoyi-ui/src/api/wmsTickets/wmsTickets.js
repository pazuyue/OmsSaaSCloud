import request from '@/utils/request'

// 查询出入库单列表
export function listTickets(query) {
  return request({
    url: '/supplychain/tickets/list',
    method: 'get',
    params: query
  })
}

// 查询出入库单详细
export function getTickets(id) {
  return request({
    url: '/supplychain/tickets/' + id,
    method: 'get'
  })
}

// 新增出入库单
export function addTickets(data) {
  return request({
    url: '/supplychain/tickets',
    method: 'post',
    data: data
  })
}

// 修改出入库单
export function updateTickets(data) {
  return request({
    url: '/supplychain/tickets',
    method: 'put',
    data: data
  })
}

// 删除出入库单
export function delTickets(id) {
  return request({
    url: '/supplychain/tickets/' + id,
    method: 'delete'
  })
}
