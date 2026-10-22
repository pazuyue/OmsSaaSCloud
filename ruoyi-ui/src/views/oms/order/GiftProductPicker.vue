<template>
  <div class="product-picker">
    <el-button size="small" :disabled="disabled" @click="open"><i class="el-icon-search" aria-hidden="true"/> {{ buttonText }}</el-button>
    <span v-if="value.length" class="muted selected-count">已选 {{ value.length }} / {{ max }} 种</span>
    <el-table v-if="showSelected && value.length" :data="selectedRows" size="small" class="selected-products">
      <el-table-column label="商品 / SKU" min-width="190"><template slot-scope="s"><strong>{{ s.row.goodsName || '正在读取商品' }}</strong><div class="muted sku">{{ s.row.skuSn }}</div><div v-if="s.row.error" class="invalid">{{ s.row.error }}</div></template></el-table-column>
      <el-table-column prop="specs" label="规格" min-width="90"/>
      <el-table-column label="操作" width="65"><template slot-scope="s"><el-button type="text" @click="remove(s.row.skuSn)">移除</el-button></template></el-table-column>
    </el-table>
    <el-dialog :title="giftOnly?'选择赠品商品':'选择购买商品'" :visible.sync="visible" width="min(900px, 96vw)" append-to-body :close-on-click-modal="false">
      <p class="muted">{{ giftOnly?'可选择已允许作为赠品的普通商品、套装和福袋；套装与福袋须已发布并启用配置。':'购买范围按所选SKU的数量及商品实付合计计算。' }} 最多 {{ max }} 种；跨页选择会保留。</p>
      <p v-if="giftOnly" class="muted"><el-link v-hasPermi="['goods:info:list']" type="primary" @click="openGoods()">前往商品设置</el-link>（新页面打开，保留当前策略）</p>
      <el-tabs v-model="tab"><el-tab-pane label="搜索商品" name="search">
        <el-form inline size="small" @submit.native.prevent="search(true)"><el-form-item><el-select v-model="field" style="width:125px"><el-option label="SKU 编码" value="skuSn"/><el-option label="商品名称" value="goodsName"/></el-select></el-form-item><el-form-item><el-input v-model="keyword" clearable placeholder="输入商品名称或SKU" @keyup.enter.native="search(true)"/></el-form-item><el-button type="primary" :loading="searching" @click="search(true)">查询</el-button></el-form>
        <el-table :data="rows" v-loading="searching" size="small" empty-text="输入条件查询商品"><el-table-column label="商品 / SKU" min-width="210"><template slot-scope="s"><strong>{{ s.row.goodsName }}</strong><span class="muted"> · {{ productLabel(s.row) }}</span><el-tag v-if="Number(s.row.isGift)===1" size="mini" type="success" style="margin-left:8px">可作赠品</el-tag><div class="muted sku">{{ s.row.skuSn }}</div></template></el-table-column><el-table-column prop="specs" label="规格" min-width="100"/><el-table-column label="选择" width="90"><template slot-scope="s"><el-checkbox :value="pending.includes(s.row.skuSn)" :disabled="resolving||(!pending.includes(s.row.skuSn)&&pending.length>=max)" @change="toggle(s.row)">{{ pending.includes(s.row.skuSn)?'已选':'选择' }}</el-checkbox></template></el-table-column></el-table>
        <pagination v-show="total>0" :total="total" :page.sync="page" :limit="10" @pagination="search(false)"/>
      </el-tab-pane><el-tab-pane label="批量粘贴SKU" name="paste"><el-input v-model="paste" type="textarea" :rows="4" placeholder="每行一个SKU，也可用逗号、空格分隔，最多200个"/><el-button size="small" :loading="resolving" style="margin:12px 0" @click="resolvePaste">校验SKU</el-button>
        <el-table v-if="pasteRows.length" :data="pasteRows" size="small"><el-table-column prop="skuSn" label="SKU" min-width="170"/><el-table-column prop="goodsName" label="商品" min-width="150"/><el-table-column label="校验结果" min-width="180"><template slot-scope="s"><span :class="s.row.valid?'valid':'invalid'">{{ s.row.valid?'可选择':s.row.error }}</span><el-button v-if="giftOnly&&s.row.goodsName&&Number(s.row.isGift)!==1" v-hasPermi="['goods:info:list']" type="text" @click="openGoods(s.row.skuSn)">开启赠品资格</el-button></template></el-table-column></el-table><el-button v-if="pasteRows.length" size="small" :disabled="!validPaste.length" style="margin-top:12px" @click="addPaste">加入有效商品（{{ validPaste.length }}）</el-button>
      </el-tab-pane></el-tabs>
      <div slot="footer" class="picker-footer"><span class="muted">已选择 {{ pending.length }} / {{ max }} 种</span><el-button @click="visible=false">取消</el-button><el-button type="primary" :loading="resolving" @click="confirm">确认选择</el-button></div>
    </el-dialog>
  </div>
</template>
<script>
import { productLabel } from '../goods/goodsPresentation'
import { giftGoodsSearch, resolveGiftGoods } from '@/api/order/gift'
export default {
  name:'GiftProductPicker',
  props:{value:{type:Array,default:()=>[]},max:{type:Number,default:100},giftOnly:Boolean,disabled:Boolean,showSelected:{type:Boolean,default:true},buttonText:{type:String,default:'选择商品'}},
  data:()=>({visible:false,tab:'search',keyword:'',field:'goodsName',page:1,total:0,rows:[],searching:false,resolving:false,pending:[],cache:{},paste:'',pasteRows:[],lookupVersion:0,searchVersion:0}),
  computed:{valueKey(){return JSON.stringify([this.value,this.giftOnly])},selectedRows(){return this.value.map(sku=>this.cache[sku]||{skuSn:sku})},validPaste(){return this.pasteRows.filter(r=>r.valid)}},
  watch:{valueKey:{immediate:true,handler(){this.hydrate()}},paste(){this.pasteRows=[]}},
  methods:{
    productLabel,
    openGoods(sku){window.open(this.$router.resolve({path:'/oms-goods/info',query:sku?{skuSn:sku}:{}}).href,'_blank','noopener')},
    remember(rows){rows.forEach(r=>this.$set(this.cache,r.skuSn,r));this.$emit('products',rows)},
    async hydrate(){const version=++this.lookupVersion;if(!this.value.length)return;const skus=this.value.slice();try{const data=(await resolveGiftGoods({skus,use:this.giftOnly?'GIFT':'PURCHASE'})).data;if(version===this.lookupVersion)this.remember(data)}catch(error){if(version===this.lookupVersion)this.remember(skus.map(sku=>({skuSn:sku,error:'商品读取失败，请重新选择'})))}},
    open(){this.pending=this.value.slice();this.paste='';this.pasteRows=[];this.visible=true},
    remove(sku){this.$emit('input',this.value.filter(v=>v!==sku))},
    async search(reset){if(!this.keyword.trim())return;if(reset)this.page=1;const version=++this.searchVersion;this.searching=true;try{const data=(await giftGoodsSearch({field:this.field,keyword:this.keyword.trim(),pageNum:this.page,pageSize:10,use:this.giftOnly?'GIFT':'PURCHASE'})).data;if(version===this.searchVersion){this.rows=data.rows||[];this.total=data.total||0}}finally{if(version===this.searchVersion)this.searching=false}},
    async toggle(row){if(this.pending.includes(row.skuSn)){this.pending=this.pending.filter(s=>s!==row.skuSn);return}this.resolving=true;try{const data=(await resolveGiftGoods({skus:[row.skuSn],use:this.giftOnly?'GIFT':'PURCHASE'})).data;this.remember(data);if(!data[0].valid)return this.$message.warning(data[0].error);if(this.pending.length<this.max)this.pending.push(row.skuSn)}finally{this.resolving=false}},
    async resolvePaste(){const source=this.paste,skus=Array.from(new Set(source.split(/[\s,，;；]+/).map(v=>v.trim()).filter(Boolean)));if(!skus.length||skus.length>200)return this.$message.warning('请粘贴1至200个SKU');this.resolving=true;try{const rows=(await resolveGiftGoods({skus,use:this.giftOnly?'GIFT':'PURCHASE'})).data;if(source===this.paste){this.pasteRows=rows;this.remember(rows)}}finally{this.resolving=false}},
    addPaste(){const values=Array.from(new Set([...this.pending,...this.validPaste.map(r=>r.skuSn)]));if(values.length>this.max)return this.$message.warning(`最多选择${this.max}种商品，请减少选择`);this.pending=values;this.$message.success(`已选择${values.length}种商品；无效SKU未加入`)},
    async confirm(){if(this.pending.length>this.max)return;if(!this.pending.length){this.$emit('input',[]);this.visible=false;return}this.resolving=true;try{const rows=(await resolveGiftGoods({skus:this.pending,use:this.giftOnly?'GIFT':'PURCHASE'})).data;this.remember(rows);const invalid=rows.find(r=>!r.valid);if(invalid)return this.$message.warning(`${invalid.skuSn}：${invalid.error}`);this.$emit('input',this.pending.slice());this.visible=false}finally{this.resolving=false}}
  }
}
</script>
<style scoped>
.muted{font-size:12px;color:#76859a;line-height:1.7}.sku{overflow-wrap:anywhere}.selected-count{margin-left:12px}.selected-products{margin-top:10px}.invalid{color:#bd3d3d;font-size:12px}.valid{color:#198263}.picker-footer{display:flex;gap:10px;align-items:center;justify-content:flex-end}.picker-footer>span{margin-right:auto}
</style>
