package com.oms.goods.service.goods;

import com.oms.goods.model.vo.export.GoodsVO;
import com.ruoyi.common.core.annotation.Excel;
import com.ruoyi.common.core.utils.poi.ExcelUtil;
import org.apache.poi.ss.usermodel.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;

/** Reads the current template and preserves real spreadsheet row numbers. */
public final class GoodsImportReader {
    private GoodsImportReader() { }
    private static String title(String value){int p=value.indexOf('(');return (p<0?value:value.substring(0,p)).trim();}
    public static List<GoodsVO> read(InputStream input) {
        try(InputStream stream=input;Workbook book=WorkbookFactory.create(stream)){
            if(book.getNumberOfSheets()==0)throw new IllegalArgumentException("Excel 中没有工作表");
            Sheet sheet=book.getSheetAt(0);Row header=sheet.getRow(0);if(header==null)throw new IllegalArgumentException("请使用商品导入模板，第一行必须是表头");
            Map<String,String> expected=new HashMap<>();for(Field field:GoodsVO.class.getDeclaredFields()){Excel annotation=field.getAnnotation(Excel.class);if(annotation!=null)expected.put(annotation.name(),title(annotation.name()));}
            Set<String> present=new HashSet<>();Map<String,Integer> positions=new HashMap<>();DataFormatter formatter=new DataFormatter();
            for(Cell cell:header){String heading=formatter.formatCellValue(cell).trim();if(heading.isEmpty())continue;if(!expected.containsKey(heading))throw new IllegalArgumentException("不支持的表头："+heading+"，请下载当前商品导入模板");String name=expected.get(heading);if(!present.add(name))throw new IllegalArgumentException("重复表头："+name);positions.put(name,cell.getColumnIndex());cell.setCellValue(heading);}
            for(String required:Arrays.asList("SKU","货号","商品名称","分类","颜色","尺码","市场价"))if(!present.contains(required))throw new IllegalArgumentException("缺少必填列："+required+"，请下载商品导入模板");
            List<Integer> rowNumbers=new ArrayList<>();for(int i=1;i<=sheet.getLastRowNum();i++){Row row=sheet.getRow(i);boolean empty=true;if(row!=null)for(Cell cell:row)if(cell.getCellType()!=CellType.BLANK){empty=false;break;}if(!empty)rowNumbers.add(i+1);if(rowNumbers.size()>5000)throw new IllegalArgumentException("每次最多导入 5000 行商品");}
            if(rowNumbers.isEmpty())throw new IllegalArgumentException("Excel 中没有商品数据");
            List<String> sourceErrors=new ArrayList<>();
            for(int rowNumber:rowNumbers){
                Row row=sheet.getRow(rowNumber-1);List<String> errors=new ArrayList<>();
                for(String name:Arrays.asList("是否福袋","是否赠品","是否套装","有效期")){
                    if(!positions.containsKey(name))continue;String value=formatter.formatCellValue(row.getCell(positions.get(name))).trim();if(value.isEmpty())continue;
                    Cell cell=row.getCell(positions.get(name));
                    if(name.equals("有效期")){if(!value.matches("\\d{1,9}")){errors.add("保质期必须为非负整数，单位天（当前值："+value+"）");cell.setCellValue(0);}}
                    else {int flag=0;if(value.equals("1") || value.equals("是"))flag=1;else if(!value.equals("0") && !value.equals("否"))errors.add(name+"只能填写 0/1 或 是/否（当前值："+value+"）");cell.setCellValue(flag);
                    }
                }
                sourceErrors.add(String.join("；",errors));
            }
            ByteArrayOutputStream normalized=new ByteArrayOutputStream();book.write(normalized);
            List<GoodsVO> rows=new ExcelUtil<>(GoodsVO.class).importExcel(new ByteArrayInputStream(normalized.toByteArray()));
            if(rows.size()!=rowNumbers.size())throw new IllegalArgumentException("Excel 数据结构异常，请使用商品导入模板");
            for(int i=0;i<rows.size();i++){rows.get(i).setSourceRowNum(rowNumbers.get(i));rows.get(i).setSourceErrors(sourceErrors.get(i));}
            return rows;
        }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("Excel 解析失败，请核对模板和数值格式");}
    }
}
