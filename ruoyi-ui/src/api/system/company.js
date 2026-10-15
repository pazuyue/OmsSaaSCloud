import request from '@/utils/request'
export const listCompanies = params => request({ url: '/system/company/list', params })
export const companyOptions = query => request({ url: '/system/company/options', params: { query } })
export const companyDetail = code => request({ url: '/system/company/detail/' + encodeURIComponent(code) })
export const companyCatalog = () => request({ url: '/system/company/catalog', method: 'post', timeout: 30000 })
export const saveCompany = data => request({ url: '/system/company', method: data.revision ? 'put' : 'post', data })
export const companyAction = (code, action, data) => request({ url: '/system/company/' + encodeURIComponent(code) + '/' + action, method: 'post', data, timeout: 60000 })

export const myCompany = () => request({ url: '/system/company/mine' })
