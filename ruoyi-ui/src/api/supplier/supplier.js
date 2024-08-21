import request from '@/utils/request'

// 查询供应商主列表
export function listSupplierIinfo(query) {
  return request({
    url: '/supplychain/supplier/list',
    method: 'get',
    params: query
  })
}

// 查询供应商主列表
export function listSupplier(query) {
  return request({
    url: '/supplychain/supplier/listSupplier',
    method: 'get',
    params: query
  })
}


// 查询供应商主详细
export function getSupplierIinfo(id) {
  return request({
    url: '/supplychain/supplier/' + id,
    method: 'get'
  })
}

// 新增供应商主
export function addSupplierIinfo(data) {
  return request({
    url: '/supplychain/supplier',
    method: 'post',
    data: data
  })
}

// 修改供应商主
export function updateSupplierIinfo(data) {
  return request({
    url: '/supplychain/supplier',
    method: 'put',
    data: data
  })
}

// 删除供应商主
export function delSupplierIinfo(id) {
  return request({
    url: '/supplychain/supplier/' + id,
    method: 'delete'
  })
}
