import request from '@/utils/request'

// 查询插件管理列表
export function listCompanyModelAssociationConfig(query) {
  return request({
    url: '/system/companyModelAssociationConfig/list',
    method: 'get',
    params: query
  })
}

// 查询插件管理详细
export function getCompanyModelAssociationConfig(id) {
  return request({
    url: '/system/companyModelAssociationConfig/' + id,
    method: 'get'
  })
}

// 新增插件管理
export function addCompanyModelAssociationConfig(data) {
  return request({
    url: '/system/companyModelAssociationConfig',
    method: 'post',
    data: data
  })
}

// 修改插件管理
export function updateCompanyModelAssociationConfig(data) {
  return request({
    url: '/system/companyModelAssociationConfig',
    method: 'put',
    data: data
  })
}

// 删除插件管理
export function delCompanyModelAssociationConfig(id) {
  return request({
    url: '/system/companyModelAssociationConfig/' + id,
    method: 'delete'
  })
}
