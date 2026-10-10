import request from '@/utils/request'

export const warehouseList = (kind, params) => request({ url: `/supplychain/${kind}/list`, method: 'get', params })
export const warehouseDetail = (kind, id) => request({ url: `/supplychain/${kind}/${id}`, method: 'get' })
export const warehouseSave = (kind, data) => request({ url: `/supplychain/${kind}`, method: data.id ? 'put' : 'post', data })
export const warehouseDelete = (kind, ids) => request({ url: `/supplychain/${kind}/${ids}`, method: 'delete' })
export const warehouseOptions = () => request({ url: '/supplychain/ownerWarehouse/options', method: 'get' })
