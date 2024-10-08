import request from '@/utils/request'

// 查询采购入库通知单列表
export function listTickets(query) {
  return request({
    url: '/supplychain/noTickets/list',
    method: 'get',
    params: query
  })
}

// 查询采购入库通知单详细
export function getTickets(id) {
  return request({
    url: '/supplychain/noTickets/' + id,
    method: 'get'
  })
}

// 查询采购入库通知单详细
export function getTicketsGoods(query) {
  return request({
    url: '/supplychain/noTicketsGoods/list',
    method: 'get',
    params: query
  })
}

// 查询采购入库通知单详细
export function getOne(noSn) {
  return request({
    url: '/supplychain/noTickets/one?no_sn=' + noSn,
    method: 'get'
  })
}

// 新增采购入库通知单
export function addTickets(data) {
  return request({
    url: '/supplychain/noTickets',
    method: 'post',
    data: data
  })
}

// 修改采购入库通知单
export function updateTickets(data) {
  return request({
    url: '/supplychain/noTickets',
    method: 'put',
    data: data
  })
}

// 删除采购入库通知单
export function delTickets(id) {
  return request({
    url: '/supplychain/noTickets/' + id,
    method: 'delete'
  })
}

export function noTicketsExamine(noSn) {
  return request({
    url: '/supplychain/noTickets/examine?no_sn=' + noSn,
    method: 'post'
  })
}

