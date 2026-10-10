import request from '@/utils/request'

// 查询产品信息列表
export function listInfo(data) {
  return request({
    url: '/goods/info/list',
    method: 'post',
    headers: { repeatSubmit: false },
    params: { pageNum: data.pageNum, pageSize: data.pageSize },
    data: data
  })
}

// 查询产品信息列表
export function exportListInfo(query) {
  return request({
    url: '/goods/goodsAdministration/list',
    method: 'post',
    headers: { repeatSubmit: false },
    params: { pageNum: query.pageNum, pageSize: query.pageSize, errorsOnly: query.errorsOnly || false },
    data: query
  })
}

export function toExamine(data) {
  return request({
    url: '/goods/goodsAdministration/toExamine',
    method: 'post',
    timeout: 120000,
    params: data
  })
}

export function goodsOptions() {
  return request({ url: '/goods/info/options', method: 'get' })
}

export function importBatch(batch) {
  return request({ url: '/goods/goodsAdministration/batch', method: 'get', params: { import_batch: batch } })
}

// 查询产品信息详细
export function getInfo(id) {
  return request({
    url: '/goods/info/' + id,
    method: 'get'
  })
}

// 新增产品信息
export function addInfo(data) {
  return request({
    url: '/goods/info',
    method: 'post',
    data: data
  })
}

// 修改产品信息
export function updateInfo(data) {
  return request({
    url: '/goods/info',
    method: 'put',
    data: data
  })
}

// 删除产品信息
export function delInfo(id) {
  return request({
    url: '/goods/info/' + id,
    method: 'delete'
  })
}

