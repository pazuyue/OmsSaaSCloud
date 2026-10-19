import request from '@/utils/request'
const base='/supplychain/wmsProcess'
export const processList=params=>request({url:base,params})
export const processOptions=params=>request({url:base+'/options',params})
export const processBatches=params=>request({url:base+'/batches',params})
export const processDetail=id=>request({url:base+'/'+id})
export const processCreate=data=>request({url:base,method:'post',data})
export const processAction=(id,action,data)=>request({url:`${base}/${id}/${action}`,method:'post',data})
