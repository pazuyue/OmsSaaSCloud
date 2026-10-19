"""Install new multi-link platform stock synchronization; no history conversion."""
from local_db import execute,HERE

def install(db):
 execute((HERE/'migrations/20261019_platform_stock_sync.sql').read_text('utf-8'),db)
 from configure_order import column
 column(db,'channel_stock_sync_group','requested_by',"VARCHAR(64) NOT NULL DEFAULT ''")
 for key in ['available_quantity','target_quantity','retained_quantity']:column(db,'channel_stock_sync_group',key,'BIGINT NULL')

def main():
 install('qm_oms_saas_channel')
 from seed_business import ensure_menu
 parent=int(execute("SELECT menu_id FROM sys_menu WHERE parent_id=0 AND path='oms-inventory' LIMIT 1").strip())
 key=ensure_menu('平台库存同步',parent,'platformStock','oms/inventory/PlatformStockSync',permission='channel:stockSync:query',order=9)
 for title,perm in [('查看','channel:stockSync:query'),('配置','channel:stockSync:edit'),('执行','channel:stockSync:execute')]:
  child=ensure_menu(title,key,'',kind='F',permission=perm)
  execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{child})')
 execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{key})')
 target='platformStockSyncTask.scanAll()'
 if execute("SELECT COUNT(*) FROM sys_job WHERE invoke_target='"+target+"'").strip()=='0':
  execute("INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by) VALUES ('平台库存同步扫描','DEFAULT','"+target+"','0 * * * * ?','2','1','0','admin')")
 print('Platform stock sync schema, menu and Quartz task installed')
if __name__=='__main__':main()
