import request from '@/utils/request'

// 查询实仓列表
export function listWmsRealStoreInfo(query) {
  return request({
    url: '/supplychain/realStore/list',
    method: 'get',
    params: query
  })
}

// 查询实仓详细
export function getWmsRealStoreInfo(id) {
  return request({
    url: '/supplychain/realStore/' + id,
    method: 'get'
  })
}

// 新增实仓
export function addWmsRealStoreInfo(data) {
  return request({
    url: '/supplychain/realStore',
    method: 'post',
    data: data
  })
}

// 修改实仓
export function updateWmsRealStoreInfo(data) {
  return request({
    url: '/supplychain/realStore',
    method: 'put',
    data: data
  })
}

// 删除实仓
export function delWmsRealStoreInfo(id) {
  return request({
    url: '/supplychain/realStore/' + id,
    method: 'delete'
  })
}
