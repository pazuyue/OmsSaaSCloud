"""New platform product registry and explicit bindings. No mapping history is inferred."""
from local_db import execute,HERE
from configure_order import column

def install(db):
 from configure_stock_sync import install as stock
 stock(db)
 execute((HERE/'migrations/20261019_platform_products.sql').read_text('utf-8'),db)
 upgrade(db)
 from configure_messaging import channel
 channel(db)
 for key,definition in [('binding_id','BIGINT NULL'),('binding_revision','BIGINT NULL'),('retired_at','DATETIME NULL')]:column(db,'channel_stock_sync_link',key,definition)
 column(db,'channel_stock_sync_link','active_item_id',"VARCHAR(32) GENERATED ALWAYS AS (CASE WHEN retired_at IS NULL THEN item_id ELSE NULL END) STORED")
 indexes=execute("SELECT index_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='channel_stock_sync_link' AND index_name='platform_target'",db)
 if indexes.strip():execute('ALTER TABLE channel_stock_sync_link DROP INDEX platform_target',db)
 if not execute("SELECT index_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='channel_stock_sync_link' AND index_name='active_platform_target'",db).strip():execute('ALTER TABLE channel_stock_sync_link ADD UNIQUE KEY active_platform_target(company_code,channel_id,active_item_id,sku_id)',db)

def upgrade(db):
 execute((HERE/'migrations/20261019_channel_automation.sql').read_text('utf-8'),db)
 for key,definition in [('binding_source',"VARCHAR(16) NOT NULL DEFAULT ''"),('binding_reason',"VARCHAR(255) NOT NULL DEFAULT ''"),('match_issue',"VARCHAR(255) NOT NULL DEFAULT ''"),('auto_bind_allowed','TINYINT NOT NULL DEFAULT 1'),('next_match_at','DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP'),('last_task_id','BIGINT NULL')]:column(db,'channel_platform_product',key,definition)
 for key,definition in [('attempts','INT NOT NULL DEFAULT 0'),('next_attempt','DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP'),('trigger_source',"VARCHAR(24) NOT NULL DEFAULT 'USER'"),('last_log_id','BIGINT NULL')]:column(db,'channel_product_pull',key,definition)
 for key,definition in [('last_log_id','BIGINT NULL')]:column(db,'channel_product_pull_item',key,definition)


def install_order(db):
 for key,definition in [('product_binding_id','BIGINT NULL'),('product_binding_revision','BIGINT NULL'),('product_match_source',"VARCHAR(24) NOT NULL DEFAULT ''")]:column(db,'oms_platform_order_line',key,definition)

def main():
 install('qm_oms_saas_channel');install_order('qm_oms_saas_order')
 from seed_business import ensure_menu
 parent=int(execute("SELECT menu_id FROM sys_menu WHERE parent_id=0 AND path='oms-channel' LIMIT 1").strip())
 execute("UPDATE sys_menu SET menu_name='店铺管理',order_num=1 WHERE parent_id="+str(parent)+" AND path='channel'")
 settings=ensure_menu('渠道设置',parent,'settings','oms/channel/ChannelSettings',permission='channel:platform:query',order=9)
 execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{settings})')
 key=ensure_menu('平台商品',parent,'platformProducts','oms/channel/PlatformProducts',permission='channel:product:query',order=3)
 for title,perm in [('查询','channel:product:query'),('拉取','channel:product:pull'),('绑定','channel:product:bind')]:
  child=ensure_menu(title,key,'',kind='F',permission=perm);execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{child})')
 execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{key})')
 if execute("SELECT COUNT(*) FROM sys_job WHERE invoke_target='platformProductTask.scanAll()'").strip()=='0':execute("INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by) VALUES ('平台商品拉取扫描','DEFAULT','platformProductTask.scanAll()','0/10 * * * * ?','2','1','0','admin')")
 print('Platform products, bindings, menu and pull task installed; existing links are not auto-bound')
if __name__=='__main__':main()
