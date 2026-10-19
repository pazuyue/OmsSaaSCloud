import request from '@/utils/request'
export function productGet(path = '', params) { return request({ url: '/channel/platformProducts' + path, method: 'get', params }) }
export function productPost(path = '', data) { return request({ url: '/channel/platformProducts' + path, method: 'post', data, headers: { repeatSubmit: false } }) }
