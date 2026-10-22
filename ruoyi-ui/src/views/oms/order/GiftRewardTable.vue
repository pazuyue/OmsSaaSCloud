<template>
  <div class="reward-table">
    <gift-product-picker :value="value.map(g=>g.sku_sn)" :max="20" gift-only :show-selected="false" button-text="选择赠品商品" @input="select" @products="remember"/>
    <el-table :data="value" size="small" empty-text="请选择本条规则赠送的商品" style="margin-top:10px">
      <el-table-column label="赠品 / SKU" min-width="180"><template slot-scope="s"><strong>{{ (catalog[s.row.sku_sn]||{}).goodsName || s.row.goods_name || s.row.sku_sn }}</strong><div class="muted sku">{{ s.row.sku_sn }}</div><span v-if="(catalog[s.row.sku_sn]||{}).error" class="invalid">{{ catalog[s.row.sku_sn].error }}</span></template></el-table-column>
      <el-table-column label="规格" min-width="80"><template slot-scope="s">{{ (catalog[s.row.sku_sn]||{}).specs || '—' }}</template></el-table-column>
      <el-table-column label="每次赠送数量" width="205"><template slot-scope="s"><el-input-number :value="s.row.quantity" :min="1" :max="unit(s.row)==='袋'?50:100000" :precision="0" size="small" style="width:135px" @change="quantity(s.$index,$event)"/> <span>{{ unit(s.row) }}</span></template></el-table-column>
      <el-table-column label="操作" width="65"><template slot-scope="s"><el-button type="text" @click="remove(s.$index)">移除</el-button></template></el-table-column>
    </el-table>
  </div>
</template>
<script>
import GiftProductPicker from './GiftProductPicker'
export default {
  name:'GiftRewardTable',components:{GiftProductPicker},props:{value:{type:Array,default:()=>[]}},data:()=>({catalog:{}}),
  methods:{
    unit(row){return (this.catalog[row.sku_sn]||{}).salesUnit||'件'},
    remember(rows){rows.forEach(r=>this.$set(this.catalog,r.skuSn,r))},
    select(skus){const old=new Map(this.value.map(g=>[g.sku_sn,g]));this.$emit('input',skus.map(sku=>({...old.get(sku),sku_sn:sku,goods_name:(this.catalog[sku]||{}).goodsName||(old.get(sku)||{}).goods_name||'',quantity:(old.get(sku)||{}).quantity||1})))},
    quantity(index,value){this.$emit('input',this.value.map((g,i)=>i===index?{...g,quantity:value}:g))},
    remove(index){this.$emit('input',this.value.filter((_,i)=>i!==index))}
  }
}
</script>
<style scoped>.muted{font-size:12px;color:#76859a}.sku{overflow-wrap:anywhere;margin-top:5px}.invalid{color:#bd3d3d;font-size:12px}</style>
