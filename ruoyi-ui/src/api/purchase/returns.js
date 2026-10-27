import request from '@/utils/request'
const root='/supplychain/purchaseReturns'
export const listReturns=params=>request({url:root,params})
export const returnSources=params=>request({url:root+'/sources',params})
export const purchaseChoices=params=>request({url:root+'/purchases',params})
export const returnCarriers=params=>request({url:root+'/carriers',params})
export const returnDetail=id=>request({url:root+'/'+id})
export const createReturn=data=>request({url:root,method:'post',data})
export const returnAction=(id,action,data={})=>request({url:root+'/'+id+'/'+action,method:'post',data,timeout:120000})

export const reclaimDetail=id=>request({url:root+"/reclaims/"+id})
export const reclaimAction=(id,action,data)=>request({url:root+"/reclaims/"+id+"/"+action,method:"post",data,timeout:120000})

// Recovery failures are rendered once with their source/quantity evidence in the review dialog.
export const checkReturnRestore=(id,requestId)=>request({url:root+'/'+id+'/restore/'+requestId+'/check',silentError:true})
export const approveReturnRestore=(id,requestId,data)=>request({url:root+'/'+id+'/restore/'+requestId+'/approve',method:'post',data,silentError:true,timeout:120000})
