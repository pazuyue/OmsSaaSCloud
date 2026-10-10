import request from '@/utils/request'

const base = '/inventory/channelInventory'
export const channelInventoryList = params => request({ url: base, params })
export const channelInventoryDetail = id => request({ url: `${base}/${id}` })
export const channelInventoryRows = (id, type, params) => request({ url: `${base}/${id}/${type}`, params })
export const channelInventoryOptions = params => request({ url: `${base}/channels`, params })
