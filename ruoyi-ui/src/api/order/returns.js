import request from '@/utils/request'
export const returnList = params => request({ url: '/order/returns', params })
export const returnDetail = params => request({ url: '/order/returns/detail', params })
export const returnDocument = params => request({ url: '/order/returns/document', params })
export const returnCatalog = () => request({ url: '/order/returns/catalog' })
export const returnExport = params => request({ url: '/order/returns/export', params, timeout: 60000 })
