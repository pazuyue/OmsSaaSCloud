<template><carrier-select v-if="active" :value="value" :context="{purchase_id:purchaseId,flow,province}" :load-options="load" @input="$emit('input',$event)" /></template>
<script>
import CarrierSelect from '@/components/CarrierSelect'
import { returnCarriers } from '@/api/purchase/returns'
export default {
 components:{CarrierSelect},
 props:{value:{type:[Number,String],default:''},purchaseId:{type:[Number,String],required:true},active:{type:Boolean,default:true},flow:{type:String,default:'INBOUND'},province:{type:String,default:''}},
 methods:{load(keyword){return returnCarriers({purchaseId:this.purchaseId,flow:this.flow,province:this.province,keyword})}}
}
</script>
