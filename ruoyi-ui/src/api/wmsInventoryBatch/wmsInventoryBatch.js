import request from '@/utils/request'

// 查询仓库批次库存列表
export function listInventoryBatch(query) {
  return request({
    url: '/inventory/wmsInventory/wmsInventoryBatch/list',
    method: 'get',
    params: query
  })
}

// 查询仓库批次库存详细
export function getInventoryBatch(id) {
  return request({
    url: '/inventory/wmsInventory/wmsInventoryBatch/' + id,
    method: 'get'
  })
}

// 新增仓库批次库存
export function addInventoryBatch(data) {
  return request({
    url: '/inventory/wmsInventory/wmsInventoryBatch',
    method: 'post',
    data: data
  })
}

// 修改仓库批次库存
export function updateInventoryBatch(data) {
  return request({
    url: '/wmsInventory/wmsInventoryBatch',
    method: 'put',
    data: data
  })
}

// 删除仓库批次库存
export function delInventoryBatch(id) {
  return request({
    url: '/inventory/wmsInventory/wmsInventoryBatch/' + id,
    method: 'delete'
  })
}
