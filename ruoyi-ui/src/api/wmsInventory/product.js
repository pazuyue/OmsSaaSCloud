import request from '@/utils/request'

const base = '/inventory/productInventory'
export const productInventoryList = params => request({ url: base, params })
export const productInventoryDetail = id => request({ url: `${base}/${id}` })
export const productInventoryRows = (id, type, params) => request({ url: `${base}/${id}/${type}`, params })
