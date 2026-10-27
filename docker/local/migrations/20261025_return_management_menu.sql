-- 仅增加退货统一查询入口和独立查询权限，不修改售后处理授权或店铺规则。
SET @return_order_menu=(SELECT menu_id FROM sys_menu WHERE parent_id=0 AND path='oms-order' LIMIT 1);
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '退货管理',@return_order_menu,8,'returns','oms/order/Returns',1,1,'C','0','0','order:return:list','list','migration',NOW(),'统一退货任务、退回包裹和实际入库单查询；合包范围按真实来源展示'
WHERE @return_order_menu IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@return_order_menu AND path='returns');
SET @return_list_menu=(SELECT menu_id FROM sys_menu WHERE parent_id=@return_order_menu AND path='returns' LIMIT 1);
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '退货详情查询',@return_list_menu,1,'#',1,1,'F','0','0','order:return:query','#','migration',NOW(),'查询退回来源及整包归属，不授予仓库或退款操作权限'
WHERE @return_list_menu IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='order:return:query');
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '退货查询导出',@return_list_menu,2,'#',1,1,'F','0','0','order:return:export','#','migration',NOW(),'导出当前筛选的退货查询结果，每次最多2000条'
WHERE @return_list_menu IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='order:return:export');
INSERT IGNORE INTO sys_role_menu(role_id,menu_id) SELECT 1,menu_id FROM sys_menu WHERE perms IN ('order:return:list','order:return:query','order:return:export');
