import request from '@/utils/request'

const base = '/inventory/wmsInventory/wmsInventoryBatch'
export const batchTraceDetail = id => request({ url: `${base}/${id}` })
export const batchTraceRows = (id, type, params) => request({ url: `${base}/${id}/${type}`, params })
export const batchSourceOrders = (id, sourceId, params) => request({ url: `${base}/${id}/sources/${sourceId}/orders`, params })
