-- 在系统库执行：统一售后配置菜单，保留原权限节点，不修改店铺规则、业务单据或库存。
SET @refund_order_menu = (SELECT menu_id FROM sys_menu WHERE parent_id=0 AND path='oms-order' LIMIT 1);
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '售后设置',@refund_order_menu,16,'afterSaleRules','ParentView',1,1,'M','0','0','','setting','migration',NOW(),'售后设置分组，集中按店铺维护退款接入、平台授权、自动处理及退货入库'
WHERE @refund_order_menu IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@refund_order_menu AND path='afterSaleRules' AND menu_type='M');
SET @refund_settings_group = (SELECT menu_id FROM sys_menu WHERE parent_id=@refund_order_menu AND path='afterSaleRules' AND menu_type='M' LIMIT 1);
UPDATE sys_menu SET menu_name='售后设置',component='ParentView',icon='setting',visible='0',status='0' WHERE menu_id=@refund_settings_group;
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT '店铺售后设置',@refund_settings_group,1,'settings','oms/order/RefundAfterSaleSettings',1,1,'C','0','0','order:refund:config','setting','migration',NOW(),'统一配置入口；概览批量读取本地状态，不主动发起平台操作'
WHERE @refund_settings_group IS NOT NULL AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE parent_id=@refund_settings_group AND path='settings' AND menu_type='C');
SET @refund_settings_page = (SELECT menu_id FROM sys_menu WHERE parent_id=@refund_settings_group AND path='settings' AND menu_type='C' LIMIT 1);
UPDATE sys_menu SET menu_name='店铺售后设置',component='oms/order/RefundAfterSaleSettings',icon='setting',visible='0',status='0',is_cache=1 WHERE menu_id=@refund_settings_page;
UPDATE sys_menu SET visible='1' WHERE parent_id=@refund_settings_group AND path='returns';
INSERT IGNORE INTO sys_role_menu(role_id,menu_id) SELECT DISTINCT r.role_id,@refund_settings_group FROM sys_role_menu r JOIN sys_menu m ON m.menu_id=r.menu_id WHERE m.perms='order:refund:config' AND @refund_settings_group IS NOT NULL;
INSERT IGNORE INTO sys_role_menu(role_id,menu_id) SELECT DISTINCT r.role_id,@refund_settings_page FROM sys_role_menu r JOIN sys_menu m ON m.menu_id=r.menu_id WHERE m.perms='order:refund:config' AND @refund_settings_page IS NOT NULL;
INSERT IGNORE INTO sys_role_menu(role_id,menu_id) SELECT 1,@refund_settings_group WHERE @refund_settings_group IS NOT NULL;
INSERT IGNORE INTO sys_role_menu(role_id,menu_id) SELECT 1,@refund_settings_page WHERE @refund_settings_page IS NOT NULL;
