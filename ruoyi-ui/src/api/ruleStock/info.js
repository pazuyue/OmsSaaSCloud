import request from '@/utils/request'

// 查询分货单基础信息列表
export function listInfo(query) {
  return request({
    url: '/inventory/ruleStock/list',
    method: 'get',
    params: query
  })
}

// 查询分货单基础信息详细
export function getInfo(id) {
  return request({
    url: '/inventory/ruleStock/' + id,
    method: 'get'
  })
}

// 查询分货单基础信息详细
export function getInfoDetails(id) {
  return request({
    url: '/inventory/ruleStockHandle/getInfoDetails/' + id,
    method: 'get'
  })
}

// 新增分货单基础信息
export function addInfo(data) {
  return request({
    url: '/inventory/ruleStock',
    method: 'post',
    data: data
  })
}

// 修改分货单基础信息
export function updateInfo(data) {
  return request({
    url: '/inventory/ruleStock',
    method: 'put',
    data: data
  })
}

// 删除分货单基础信息
export function delInfo(id) {
  return request({
    url: '/inventory/ruleStock/' + id,
    method: 'delete'
  })
}

// 设置分货单规则
export function setRule(data) {
  return request({
    url: '/inventory/ruleStockHandle/setRule',
    method: 'post',
    data: data
  })
}

// 审核
export function toExamine(id) {
  return request({
    url: '/inventory/ruleStockHandle/toExamine/' + id,
    method: 'get'
  })


}

export function getRuleStockGoodsInfoList(id, params) {
  return request({
    url: '/inventory/ruleStockGoods/list/'+id,
    method: 'get',
    params: params
  })
}
