import request from '@/utils/request'

// 查询仓库库存列表
export function listInventory(query) {
  return request({
    url: '/inventory/wmsInventory/list',
    method: 'get',
    params: query
  })
}

// 查询仓库库存详细
export function getInventory(id) {
  return request({
    url: '/inventory/wmsInventory/' + id,
    method: 'get'
  })
}

// 新增仓库库存
export function addInventory(data) {
  return request({
    url: '/inventory/wmsInventory',
    method: 'post',
    data: data
  })
}

// 修改仓库库存
export function updateInventory(data) {
  return request({
    url: '/inventory/wmsInventory',
    method: 'put',
    data: data
  })
}

// 删除仓库库存
export function delInventory(id) {
  return request({
    url: '/inventory/wmsInventory/' + id,
    method: 'delete'
  })
}
