import request from '@/utils/request'
const root = '/order/platform-documents'
export const platformDocumentOptions = () => request({ url: `${root}/options` })
export const platformDocumentList = (kind, params) => request({ url: `${root}/list`, params: { ...params, kind } })
export const relatedPlatformDocuments = params => request({ url: `${root}/related`, params })
