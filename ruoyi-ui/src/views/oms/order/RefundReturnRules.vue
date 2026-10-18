<template>
  <div class="app-container return-rules" v-loading="loading">
    <p class="intro">退货规则按店铺保存，适用于该店铺的退货退款履约，与平台数据接入独立管理。</p>
    <div class="shop-toolbar">
      <el-form inline size="small" @submit.native.prevent>
        <el-form-item label="规则所属店铺"><el-select v-model="channel" filterable clearable :disabled="busy||loading" placeholder="请选择店铺" @change="selectShop"><el-option v-for="shop in shops" :key="shop.channel_id" :value="String(shop.channel_id)" :label="shop.channel_name+' · '+shop.channel_id"/></el-select></el-form-item>
      </el-form>
      <router-link v-if="returnRefundId" :to="{path:'/oms-order/businessRefunds',query:{id:returnRefundId}}">返回退款单</router-link>
    </div>
    <el-alert v-if="error" :title="error" type="warning" show-icon :closable="false"/>
    <refund-parcel-settings v-if="selectedShop" :key="selectedShop.channel_id" ref="rules" @busy="busy=$event"/>
    <el-empty v-else-if="!loading&&!error" :description="shops.length?'选择店铺后查看包裹关联、仓库下发及实收分配规则':'当前公司暂无可配置的天猫店铺'"/>
  </div>
</template>
<script>
import { refundReturnRuleShops } from '@/api/order/refund'
import RefundParcelSettings from './RefundParcelSettings'
export default {
  name:'RefundReturnRules',components:{RefundParcelSettings},
  data:()=>({shops:[],channel:'',loading:false,busy:false,error:''}),
  computed:{selectedShop(){return this.shops.find(s=>String(s.channel_id)===this.channel)},returnRefundId(){const id=String(this.$route.query.refundId||'');return /^\d+$/.test(id)?id:''}},
  watch:{'$route.query.channelId'(){this.openRequestedShop()}},
  async created(){this.loading=true;try{this.shops=(await refundReturnRuleShops()).data.filter(s=>s.channel_type==='TM');this.openRequestedShop()}catch(_){this.error='店铺列表加载失败，请刷新页面'}finally{this.loading=false}},
  methods:{
    selectShop(value){this.$router.replace({path:'/oms-order/afterSaleRules/returns',query:{...(value?{channelId:value}:{}),...(this.returnRefundId?{refundId:this.returnRefundId}:{})}})},
    openRequestedShop(){this.busy=false;this.channel=String(this.$route.query.channelId||'');this.error=this.channel&&!this.selectedShop?'未找到当前公司下的指定店铺，请重新选择':'';if(this.selectedShop)this.$nextTick(()=>{if(this.$refs.rules)this.$refs.rules.open(this.selectedShop)})}
  }
}
</script>
<style scoped>.intro{color:#61728a;line-height:1.8;margin:0 0 20px}.shop-toolbar{display:flex;justify-content:space-between;align-items:center;gap:16px;flex-wrap:wrap}.shop-toolbar .el-select{width:360px;max-width:80vw}.shop-toolbar a{color:#1677ff;font-size:13px}</style>
