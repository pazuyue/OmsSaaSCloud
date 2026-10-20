<template>
  <div><el-button size="small" :disabled="disabled" @click="open">{{ value ? `已选 ${value} · 更换商品` : '选择 OMS 商品' }}</el-button>
    <el-dialog title="选择赠品 SKU" :visible.sync="visible" width="850px" append-to-body :close-on-click-modal="false">
      <el-alert title="赠品独立预占和扣减库存，销售金额为零。请选择可独立管理库存的实物 SKU。" type="info" :closable="false"/>
      <el-form inline size="small" style="margin-top:16px" @submit.native.prevent="search(true)"><el-form-item><el-select v-model="field" style="width:140px"><el-option label="SKU 编码" value="skuSn"/><el-option label="商品名称" value="goodsName"/></el-select></el-form-item><el-form-item><el-input v-model="keyword" clearable placeholder="输入编码或名称" @keyup.enter.native="search(true)"/></el-form-item><el-button type="primary" @click="search(true)">查询</el-button></el-form>
      <el-table v-loading="loading" :data="rows" empty-text="输入条件查询 OMS 商品"><el-table-column label="商品 / SKU" min-width="280"><template slot-scope="s"><strong>{{ s.row.goodsName }}</strong><div class="hint">{{ s.row.skuSn }}</div></template></el-table-column><el-table-column prop="specs" label="规格" min-width="180"/><el-table-column label="操作" width="100"><template slot-scope="s"><el-button type="text" @click="choose(s.row)">选择</el-button></template></el-table-column></el-table>
      <pagination v-show="total>0" :total="total" :page.sync="page" :limit="limit" @pagination="search(false)"/>
    </el-dialog>
  </div>
</template>
<script>
import { giftGoodsSearch } from '@/api/order/gift'
export default { props:{value:String,disabled:Boolean},data:()=>({visible:false,keyword:'',field:'skuSn',page:1,limit:10,total:0,rows:[],loading:false}),methods:{open(){this.visible=true;this.keyword=this.value||'';this.rows=[];this.total=0;if(this.keyword)this.search(true)},async search(reset){if(!this.keyword.trim())return;if(reset)this.page=1;this.loading=true;try{const d=(await giftGoodsSearch({field:this.field,keyword:this.keyword,pageNum:this.page,pageSize:this.limit})).data;this.rows=d.rows||[];this.total=d.total||0}finally{this.loading=false}},choose(row){this.$emit('input',row.skuSn);this.$emit('selected',row);this.visible=false}} }
</script>
<style scoped>.hint{font-size:12px;color:#8491a3;margin-top:5px}</style>
