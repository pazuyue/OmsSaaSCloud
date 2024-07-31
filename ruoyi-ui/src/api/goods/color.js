import request from '@/utils/request'

// 查询商品颜色列表
export function listColor(query) {
  return request({
    url: '/goods/color/list',
    method: 'get',
    params: query
  })
}

// 查询商品颜色详细
export function getColor(id) {
  return request({
    url: '/goods/color/' + id,
    method: 'get'
  })
}

// 新增商品颜色
export function addColor(data) {
  return request({
    url: '/goods/color',
    method: 'post',
    data: data
  })
}

// 修改商品颜色
export function updateColor(data) {
  return request({
    url: '/goods/color',
    method: 'put',
    data: data
  })
}

// 删除商品颜色
export function delColor(id) {
  return request({
    url: '/goods/color/' + id,
    method: 'delete'
  })
}
