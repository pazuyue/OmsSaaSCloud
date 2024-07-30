import request from '@/utils/request'

// 查询商品尺码列表
export function listSize(query) {
  return request({
    url: '/goods/size/list',
    method: 'get',
    params: query
  })
}

// 查询商品尺码详细
export function getSize(id) {
  return request({
    url: '/goods/size/' + id,
    method: 'get'
  })
}

// 新增商品尺码
export function addSize(data) {
  return request({
    url: '/goods/size',
    method: 'post',
    data: data
  })
}

// 修改商品尺码
export function updateSize(data) {
  return request({
    url: '/goods/size',
    method: 'put',
    data: data
  })
}

// 删除商品尺码
export function delSize(id) {
  return request({
    url: '/goods/size/' + id,
    method: 'delete'
  })
}
