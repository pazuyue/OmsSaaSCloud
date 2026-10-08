import request from '@/utils/request'

export const inventoryList = params => request({ url: '/inventory/wmsInventory/list', params })
export const inventoryDetail = id => request({ url: `/inventory/wmsInventory/${id}` })
export const inventoryHistory = (id, params) => request({ url: `/inventory/wmsInventory/${id}/history`, params })
export const adjustInventory = data => request({ url: '/inventory/wmsInventory/adjust', method: 'post', data })
export const lookupGoods = params => request({ url: '/goods/info/inventoryLookup', params })
export const lookupStores = params => request({ url: '/supplychain/simulationStore/inventoryLookup', params })
