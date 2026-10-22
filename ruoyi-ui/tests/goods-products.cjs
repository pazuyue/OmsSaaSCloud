const assert=require('assert/strict'),fs=require('fs'),path=require('path'),babel=require('@babel/core'),compiler=require('vue-template-compiler')
const base=path.join(__dirname,'../src/views/oms')
const mod={exports:{}}
const code=babel.transformSync(fs.readFileSync(path.join(base,'goods/goodsPresentation.js'),'utf8'),{configFile:false,babelrc:false,plugins:[require.resolve('@babel/plugin-transform-modules-commonjs')]}).code
new Function('module','exports',code)(mod,mod.exports)
const {productType,productLabel,productPayload,composition,giftEligibility}=mod.exports
for(const type of ['NORMAL','BUNDLE','LUCKY_BAG'])for(const isGift of [0,1]){
 const input={productType:type,isGift,isFd:1,isPackage:1},row=productPayload(input)
 assert.equal(row.isGift,isGift);assert.equal(row.isFd,type==='LUCKY_BAG'?1:0);assert.equal(row.isPackage,type==='BUNDLE'?1:0)
 assert.equal(productType(row),type);assert.ok(productLabel(row));assert.equal(input.isFd,1)
}
assert.equal(giftEligibility({productType:'NORMAL',isGift:1}),'允许作为赠品')
assert.equal(giftEligibility({productType:'BUNDLE',isGift:1}),'已开启 · 待发布配置')
assert.equal(composition({productType:'BUNDLE',inventoryMode:'STOCKED',compositionRevision:2,compositionEnabled:1}).label,'按整套成品库存')
assert.equal(composition({productType:'BUNDLE',inventoryMode:'VIRTUAL',compositionRevision:2,compositionEnabled:0}).ready,false)
assert.equal(composition({productType:'LUCKY_BAG',luckyMode:'RANDOM',compositionRevision:2,compositionEnabled:1}).label,'下单时确定内容')
assert.equal(composition({productType:'LUCKY_BAG',luckyMode:'PRESET',compositionRevision:2,compositionEnabled:1}).label,'先生成组合，再按袋领取')
for(const file of ['goods/components/GoodsInfo.vue','order/GiftContents.vue','order/GiftProgressCard.vue','order/RefundGiftLines.vue','order/GiftRewardTable.vue','order/GiftProductPicker.vue']){
 const source=compiler.parseComponent(fs.readFileSync(path.join(base,file),'utf8'))
 assert.deepEqual(compiler.compile(source.template.content).errors,[],file)
 assert.ok(!source.template.content.includes('是否福袋'))
}
console.log('PASS exclusive product form, independent gift eligibility, configuration readiness, stock modes and six Vue templates')
