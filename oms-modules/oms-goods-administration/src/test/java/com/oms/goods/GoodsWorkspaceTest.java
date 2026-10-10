package com.oms.goods;

import com.oms.goods.controller.goods.*;
import com.oms.goods.model.vo.export.GoodsVO;
import com.oms.goods.service.goods.GoodsReferenceClient;
import com.oms.goods.service.goods.impl.GoodsWorkspaceService;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.bind.annotation.*;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Runs against an isolated MySQL schema, never the local business database. */
public class GoodsWorkspaceTest {
    static AnnotationConfigApplicationContext context;static JdbcTemplate db;static GoodsWorkspaceService goods;
    static AtomicBoolean referenced=new AtomicBoolean(),unavailable=new AtomicBoolean();
    long category,color,size;
    @Configuration @EnableTransactionManagement static class Config {
        @Bean DataSource dataSource(){String url=System.getenv("GOODS_TEST_URL");if(url==null || !url.contains("/goods_workspace_test?"))throw new IllegalStateException("Disposable schema required");return new DriverManagerDataSource(url,"root",System.getenv("INVENTORY_TEST_PASSWORD"));}
        @Bean JdbcTemplate jdbcTemplate(DataSource ds){return new JdbcTemplate(ds);}
        @Bean PlatformTransactionManager transactionManager(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean GoodsWorkspaceService goods(){return new GoodsWorkspaceService();}
        @Bean GoodsReferenceClient references(){return (company,skus,source)->{if(unavailable.get())throw new IllegalStateException("offline");return R.ok(referenced.get()?skus:Collections.emptyList());};}
    }
    @BeforeAll static void init(){context=new AnnotationConfigApplicationContext(Config.class);db=context.getBean(JdbcTemplate.class);goods=context.getBean(GoodsWorkspaceService.class);}
    @AfterAll static void close(){context.close();}
    @BeforeEach void seed(){
        for(String table:Arrays.asList("goods_import_row","goods_import_batch","goods_sku_sn_info","goods_category","goods_color","goods_size","goods_master_lock"))db.update("DELETE FROM "+table);
        referenced.set(false);unavailable.set(false);
        long root=goods.saveMaster("QM","category",data("name","服装","pid",0));long second=goods.saveMaster("QM","category",data("name","上装","pid",root));category=goods.saveMaster("QM","category",data("name","衬衫","pid",second));
        color=goods.saveMaster("QM","color",data("colorName","白色","outColorCode","WHITE"));size=goods.saveMaster("QM","size",data("sizeName","M","outSizeCode","MEDIUM","sortOrder",20));
    }
    static Map<String,Object> data(Object... items){Map<String,Object> map=new HashMap<>();for(int i=0;i<items.length;i+=2)map.put((String)items[i],items[i+1]);return map;}
    Map<String,Object> product(String sku){return data("skuSn",sku,"goodsSn","STYLE-1","goodsName","测试衬衫","categoryCode",category,"colorCode",color,"sizeCode",size,"marketPrice",BigDecimal.ZERO,"validity","30");}
    GoodsVO imported(String sku){GoodsVO vo=new GoodsVO();vo.setSkuSn(sku);vo.setGoodsSn("STYLE-1");vo.setGoodsName("导入衬衫");vo.setCategoryName("服装/上装/衬衫");vo.setColorName("白色");vo.setSizeName("M");vo.setMarketPrice(BigDecimal.ZERO);vo.setValidity(30);return vo;}
    long count(){return db.queryForObject("SELECT COUNT(*) FROM goods_sku_sn_info",Long.class);}
    @Test void createEditRenameAndFilterKeepCorrectAssociations(){
        long id=goods.saveGoods("QM",product("SKU-1"),"tester");goods.saveMaster("QM","color",data("id",color,"colorName","米白","outColorCode","WHITE"));
        Map<String,Object> result=goods.goodsDetail("QM",id);assertEquals("米白",result.get("colorName"));assertEquals("服装 / 上装 / 衬衫",result.get("categoryPath"));
        assertEquals(1,goods.goodsPage("QM",data("colorCode",color),1,10).getTotal());assertEquals(0,goods.goodsPage("QM",data("colorCode",999999),1,10).getTotal());
        Map<String,Object> edit=product("SKU-1");edit.put("id",id);edit.put("goodsName","改名商品");goods.saveGoods("QM",edit,"tester");assertEquals("改名商品",goods.goodsDetail("QM",id).get("goodsName"));
    }
    @Test void sharedStyleOptionalBarcodeAndZeroPriceAreAllowed(){goods.saveGoods("QM",product("SKU-1"),"tester");goods.saveGoods("QM",product("SKU-2"),"tester");assertEquals(2,count());}
    @Test void immutableSkuAndCompanyOwnedReferencesAreEnforced(){long id=goods.saveGoods("QM",product("SKU-1"),"tester");Map<String,Object> edit=product("SKU-CHANGED");edit.put("id",id);assertThrows(IllegalArgumentException.class,()->goods.saveGoods("QM",edit,"tester"));assertThrows(IllegalArgumentException.class,()->goods.goodsDetail("OTHER",id));assertThrows(IllegalArgumentException.class,()->goods.saveGoods("OTHER",product("OTHER-SKU"),"tester"));}
    @Test void duplicatesAreCheckedWithinCompanyAndBarcodeIsUnique(){Map<String,Object> first=product("SKU-1");first.put("barcodeSn","BAR-1");goods.saveGoods("QM",first,"tester");assertThrows(IllegalArgumentException.class,()->goods.saveGoods("QM",first,"tester"));Map<String,Object> second=product("SKU-2");second.put("barcodeSn","BAR-1");assertThrows(IllegalArgumentException.class,()->goods.saveGoods("QM",second,"tester"));assertEquals(1,count());}
    @Test void referencedMastersAndParentsCannotBeDeleted(){goods.saveGoods("QM",product("SKU-1"),"tester");for(String kind:Arrays.asList("category","color","size")){long id=kind.equals("category")?category:kind.equals("color")?color:size;assertThrows(IllegalArgumentException.class,()->goods.deleteMaster("QM",kind,Collections.singletonList(id)));}long parent=((Number)goods.masterDetail("QM","category",category).get("pid")).longValue();assertThrows(IllegalArgumentException.class,()->goods.deleteMaster("QM","category",Collections.singletonList(parent)));}
    @Test void categorySearchKeepsAncestorsAndParentFilterIncludesGoods(){goods.saveGoods("QM",product("SKU-1"),"tester");List<Map<String,Object>> rows=goods.masterList("QM","category",data("name","衬衫"));assertEquals(3,rows.size());long root=((Number)rows.get(0).get("id")).longValue();assertEquals(1,goods.goodsPage("QM",data("categoryCode",root),1,10).getTotal());assertEquals(1,((Number)rows.get(0).get("goodsCount")).intValue());}
    @Test void categoryCycleExcessDepthAndSiblingDuplicateAreRejected(){long parent=((Number)goods.masterDetail("QM","category",category).get("pid")).longValue();assertThrows(IllegalArgumentException.class,()->goods.saveMaster("QM","category",data("name","第四级","pid",category)));assertThrows(IllegalArgumentException.class,()->goods.saveMaster("QM","category",data("id",parent,"name","上装","pid",category)));assertThrows(IllegalArgumentException.class,()->goods.saveMaster("QM","category",data("name","衬衫","pid",parent)));}
    @Test void importShowsOriginalRowsUnknownMastersAndFileDuplicatesWithoutCreatingMasters(){GoodsVO a=imported("SKU-1"),b=imported("SKU-1");b.setColorName("未知颜色");String batch=goods.preview("QM",Arrays.asList(a,b),"tester");assertEquals(1,((Number)goods.batch("QM",batch).get("errorRows")).intValue());Map<?,?> bad=(Map<?,?>)goods.previewRows("QM",batch,1,10,true).getRows().get(0);assertEquals(3,((Number)bad.get("rowNum")).intValue());assertTrue(bad.get("notes").toString().contains("未知颜色"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM goods_color",Integer.class));assertThrows(IllegalArgumentException.class,()->goods.confirm("QM",batch,"tester"));assertEquals(0,count());}
    @Test void importConfirmIsAtomicIdempotentAndPreservesValidity(){String batch=goods.preview("QM",Arrays.asList(imported("SKU-1"),imported("SKU-2")),"tester");goods.confirm("QM",batch,"tester");goods.confirm("QM",batch,"tester");assertEquals(2,count());assertEquals("30",db.queryForObject("SELECT validity FROM goods_sku_sn_info WHERE sku_sn='SKU-1'",String.class));assertEquals("COMPLETED",goods.batch("QM",batch).get("status"));}
    @Test void changedDataBetweenPreviewAndConfirmCannotPartiallyInsert(){String batch=goods.preview("QM",Arrays.asList(imported("SKU-1"),imported("SKU-2")),"tester");goods.saveGoods("QM",product("SKU-2"),"tester");assertThrows(IllegalArgumentException.class,()->goods.confirm("QM",batch,"tester"));assertEquals(1,count());assertEquals("PREVIEW",goods.batch("QM",batch).get("status"));}
    @Test void batchAndMasterReadsCannotCrossCompanies(){String batch=goods.preview("QM",Collections.singletonList(imported("SKU-1")),"tester");assertThrows(IllegalArgumentException.class,()->goods.batch("OTHER",batch));assertThrows(IllegalArgumentException.class,()->goods.previewRows("OTHER",batch,1,10,false));assertThrows(IllegalArgumentException.class,()->goods.confirm("OTHER",batch,"tester"));assertThrows(IllegalArgumentException.class,()->goods.masterDetail("OTHER","color",color));assertEquals(0,goods.masterList("OTHER","color",Collections.emptyMap()).size());}
    @Test void ambiguousCategoryNameRequiresPath(){long root=goods.saveMaster("QM","category",data("name","童装","pid",0));long second=goods.saveMaster("QM","category",data("name","上装","pid",root));goods.saveMaster("QM","category",data("name","衬衫","pid",second));GoodsVO vo=imported("SKU-1");vo.setCategoryName("衬衫");String bad=goods.preview("QM",Collections.singletonList(vo),"tester");assertEquals(1,((Number)goods.batch("QM",bad).get("errorRows")).intValue());String good=goods.preview("QM",Collections.singletonList(imported("SKU-2")),"tester");goods.confirm("QM",good,"tester");assertEquals(1,count());}
    @Test void paginationAndSizeBusinessOrderAreStable(){for(int i=0;i<12;i++)goods.saveGoods("QM",product("SKU-"+i),"tester");assertEquals(5,goods.goodsPage("QM",Collections.emptyMap(),2,5).getRows().size());assertEquals(12,goods.goodsPage("QM",Collections.emptyMap(),2,5).getTotal());goods.saveMaster("QM","size",data("sizeName","S","outSizeCode","SMALL","sortOrder",10));assertEquals("S",goods.masterList("QM","size",Collections.emptyMap()).get(0).get("sizeName"));}
    @Test void malformedPriceAndValidityAndUnownedUpdateAreRejected(){Map<String,Object> data=product("SKU-1");data.put("marketPrice",-1);assertThrows(IllegalArgumentException.class,()->goods.saveGoods("QM",data,"tester"));data.put("marketPrice",0);data.put("validity","1.5");assertThrows(IllegalArgumentException.class,()->goods.saveGoods("QM",data,"tester"));data.put("validity","30");data.put("id",99999);assertThrows(IllegalArgumentException.class,()->goods.saveGoods("QM",data,"tester"));}
    @Test void deletionChecksInventoryAndFailsClosedWhenCheckUnavailable(){long id=goods.saveGoods("QM",product("SKU-1"),"tester");referenced.set(true);assertThrows(IllegalArgumentException.class,()->goods.deleteGoods("QM",Collections.singletonList(id)));referenced.set(false);unavailable.set(true);assertThrows(IllegalArgumentException.class,()->goods.deleteGoods("QM",Collections.singletonList(id)));unavailable.set(false);goods.deleteGoods("QM",Collections.singletonList(id));assertEquals(0,count());}
    @Test void concurrentDuplicateCreateHasOneWinner() throws Exception {ExecutorService pool=Executors.newFixedThreadPool(2);try{Callable<Boolean> create=()->{try{goods.saveGoods("QM",product("RACE"),"tester");return true;}catch(IllegalArgumentException e){return false;}};Future<Boolean> a=pool.submit(create),b=pool.submit(create);assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));assertEquals(1,count());}finally{pool.shutdownNow();}}
    @Test void concurrentImportConfirmationWritesOnlyOnce() throws Exception {String batch=goods.preview("QM",Arrays.asList(imported("SKU-1"),imported("SKU-2")),"tester");ExecutorService pool=Executors.newFixedThreadPool(2);try{Future<?> a=pool.submit(()->goods.confirm("QM",batch,"a")),b=pool.submit(()->goods.confirm("QM",batch,"b"));a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);assertEquals(2,count());}finally{pool.shutdownNow();}}
    @Test void publicGoodsRoutesAllDeclarePermissions(){for(Class<?> type:Arrays.asList(GoodsController.class,GoodsCategoryController.class,GoodsColorController.class,GoodsSizeController.class,GoodsSkuSnInfoController.class))Arrays.stream(type.getDeclaredMethods()).filter(m->m.isAnnotationPresent(GetMapping.class)||m.isAnnotationPresent(PostMapping.class)||m.isAnnotationPresent(PutMapping.class)||m.isAnnotationPresent(DeleteMapping.class)).forEach(m->assertNotNull(m.getAnnotation(RequiresPermissions.class),type.getSimpleName()+"."+m.getName()));}
    @Test void currentTemplateAndBlankRowsPreserveOriginalExcelPositions() throws Exception {
        try(org.apache.poi.ss.usermodel.Workbook book=new org.apache.poi.xssf.usermodel.XSSFWorkbook()){
            org.apache.poi.ss.usermodel.Sheet sheet=book.createSheet();String[] headers={"SKU(系统唯一，必填)","货号(系统唯一，必填)","商品名称(必填)","分类(第三级分类名称，必填)","颜色(必填)","尺码(必填)","市场价(≥0，必填)"};String[] values={"XLS-SKU","STYLE-1","衬衫","服装/上装/衬衫","白色","M","0"};
            sheet.createRow(0);for(int i=0;i<headers.length;i++)sheet.getRow(0).createCell(i).setCellValue(currentHeading(headers[i]));sheet.createRow(3);for(int i=0;i<values.length;i++)sheet.getRow(3).createCell(i).setCellValue(values[i]);
            java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();book.write(output);List<GoodsVO> rows=com.oms.goods.service.goods.GoodsImportReader.read(new java.io.ByteArrayInputStream(output.toByteArray()));assertEquals(1,rows.size());assertEquals(4,rows.get(0).getSourceRowNum());String batch=goods.preview("QM",rows,"tester");Map<?,?> row=(Map<?,?>)goods.previewRows("QM",batch,1,10,false).getRows().get(0);assertEquals(4,((Number)row.get("rowNum")).intValue());goods.confirm("QM",batch,"tester");assertEquals(1,count());
        }
    }
    @Test void wrongSpreadsheetHeadersFailBeforeCreatingBatch() throws Exception {
        try(org.apache.poi.ss.usermodel.Workbook book=new org.apache.poi.xssf.usermodel.XSSFWorkbook()){book.createSheet().createRow(0).createCell(0).setCellValue("错误表头");java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();book.write(output);assertThrows(IllegalArgumentException.class,()->com.oms.goods.service.goods.GoodsImportReader.read(new java.io.ByteArrayInputStream(output.toByteArray())));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM goods_import_batch",Integer.class));}
    }
    @Test void exportsConvertConnectorLocalDateTimeAndKeepNames() {
        long id=goods.saveGoods("QM",product("EXPORT-SKU"),"tester");Map<String,Object> row=goods.goodsDetail("QM",id);row.put("createTime",java.time.LocalDateTime.of(2026,10,9,12,0));
        com.oms.goods.model.vo.export.GoodsExportRow exported=goods.convert(row,com.oms.goods.model.vo.export.GoodsExportRow.class);assertEquals("服装 / 上装 / 衬衫",exported.getCategoryPath());assertEquals("白色",exported.getColorName());
        Map<String,Object> colorRow=goods.masterDetail("QM","color",color);colorRow.put("modifyTime",java.time.LocalDateTime.of(2026,10,9,12,0));assertNotNull(goods.convert(colorRow,com.oms.goods.model.entity.goods.GoodsColor.class).getModifyTime());
    }
    @Test void spreadsheetAttributesAcceptYesNoAndRejectInvalidValuesWithoutSilentlyDefaulting() throws Exception {
        try(org.apache.poi.ss.usermodel.Workbook book=new org.apache.poi.xssf.usermodel.XSSFWorkbook()){
            org.apache.poi.ss.usermodel.Sheet sheet=book.createSheet();String[] headers={"SKU","货号","商品名称","分类","颜色","尺码","市场价","是否赠品","是否福袋","是否套装","有效期"};String[] values={"FLAGS-SKU","STYLE-1","衬衫","服装/上装/衬衫","白色","M","0","是","否","1","30"};
            sheet.createRow(0);sheet.createRow(1);sheet.createRow(2);for(int i=0;i<headers.length;i++){sheet.getRow(0).createCell(i).setCellValue(currentHeading(headers[i]));sheet.getRow(1).createCell(i).setCellValue(values[i]);sheet.getRow(2).createCell(i).setCellValue(values[i]);}sheet.getRow(2).getCell(0).setCellValue("FLAGS-BAD");sheet.getRow(2).getCell(7).setCellValue("未知");sheet.getRow(2).getCell(10).setCellValue("1.5");
            java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();book.write(output);List<GoodsVO> rows=com.oms.goods.service.goods.GoodsImportReader.read(new java.io.ByteArrayInputStream(output.toByteArray()));assertEquals(1,rows.get(0).getIsGift());assertEquals(0,rows.get(0).getIsFd());assertEquals(1,rows.get(0).getIsPackage());String bad=goods.preview("QM",rows,"tester");assertEquals(1,((Number)goods.batch("QM",bad).get("errorRows")).intValue());String notes=goods.importErrors("QM",bad).get(0).get("notes").toString();assertTrue(notes.contains("未知"));assertTrue(notes.contains("1.5"));String good=goods.preview("QM",Collections.singletonList(rows.get(0)),"tester");goods.confirm("QM",good,"tester");assertEquals(1,db.queryForObject("SELECT is_package FROM goods_sku_sn_info WHERE sku_sn='FLAGS-SKU'",Integer.class));
        }
    }
    static String currentHeading(String name){String prefix=name.split("\\(",2)[0];for(java.lang.reflect.Field f:GoodsVO.class.getDeclaredFields()){com.ruoyi.common.core.annotation.Excel x=f.getAnnotation(com.ruoyi.common.core.annotation.Excel.class);if(x!=null&&x.name().startsWith(prefix+"("))return x.name();}throw new IllegalArgumentException(name);}
    @Test void oldTemplateHeadingsAreRejected() throws Exception {
        try(org.apache.poi.ss.usermodel.Workbook book=new org.apache.poi.xssf.usermodel.XSSFWorkbook()){book.createSheet().createRow(0).createCell(0).setCellValue("SKU(系统唯一，必填)");java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();book.write(bytes);assertThrows(IllegalArgumentException.class,()->com.oms.goods.service.goods.GoodsImportReader.read(new java.io.ByteArrayInputStream(bytes.toByteArray())));}
    }

}
