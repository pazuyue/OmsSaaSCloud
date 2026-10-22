"""Reliable work notification schema and local message task menu. No historical work is emitted."""
from local_db import execute,HERE
from configure_order import column

def install(db):
 execute((HERE/'migrations/20261019_messaging.sql').read_text('utf-8'),db)
 for name,definition in [("problem_code","VARCHAR(32) NOT NULL DEFAULT 'NONE'"),("parent_event_id","VARCHAR(40) NOT NULL DEFAULT ''")]:column(db,"oms_message_outbox",name,definition)
 column(db,"oms_message_outbox","source_trace_id","VARCHAR(256) NULL")
 column(db,"oms_message_attempt","sw_trace_id","VARCHAR(256) NULL")
 for name,keys in [('kind_due','company_code,service_code,message_type,process_state,next_process_at,id'),('kind_recent','company_code,service_code,message_type,completed_at')]:
  if not execute("SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='oms_message_outbox' AND index_name='"+name+"' LIMIT 1",db).strip():execute('ALTER TABLE oms_message_outbox ADD KEY '+name+'('+keys+')',db)

def channel(db):
 install(db);column(db,'channel_product_pull','work_version','BIGINT NOT NULL DEFAULT 1')
def order(db):
 install(db)
 for table,name in [('oms_order_pull_task','work_version'),('oms_platform_order','intake_version'),('oms_refund_sync','work_version'),('oms_platform_refund','intake_version'),('oms_exchange_sync','work_version'),('oms_platform_exchange','intake_version')]:column(db,table,name,'BIGINT NOT NULL DEFAULT 1')
 for name,definition in [('phase',"VARCHAR(12) NOT NULL DEFAULT 'LIST'"),('page_complete','TINYINT NOT NULL DEFAULT 0')]:column(db,'oms_exchange_sync',name,definition)
 if not execute("SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='oms_exchange_sync_item' AND index_name='pending_items' LIMIT 1",db).strip():execute('ALTER TABLE oms_exchange_sync_item ADD KEY pending_items(company_code,task_id,result,id)',db)
 column(db,'oms_platform_exchange','conversion_requested','TINYINT NOT NULL DEFAULT 0')
 column(db,'oms_platform_refund','conversion_requested','TINYINT NOT NULL DEFAULT 0')
 for name,definition in [('work_token','VARCHAR(40) NULL'),('work_until','DATETIME NULL')]:column(db,'oms_order_sales_document_sync',name,definition)
def management(db='ry-cloud'):
 execute((HERE/'migrations/20261019_queue_management.sql').read_text('utf-8'),db)
 execute((HERE/'migrations/20261019_message_exceptions.sql').read_text('utf-8'),db)
 if not execute("SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='sys_mq_dead_letter' AND index_name='pending_queue' LIMIT 1",db).strip():execute('ALTER TABLE sys_mq_dead_letter ADD KEY pending_queue(business_type,verified,business_state,reviewed_at)',db)

def main():
 management()
 channel('qm_oms_saas_channel');order('qm_oms_saas_order')
 from seed_business import ensure_menu
 parent=int(execute("SELECT menu_id FROM sys_menu WHERE parent_id=0 AND path='monitor' LIMIT 1").strip())
 queue=ensure_menu('消息队列',parent,'queues','oms/messaging/QueueManagement',permission='oms:queue:query',order=7)
 for title,perm in [('查询','oms:queue:query'),('配置','oms:queue:edit')]:
  child=ensure_menu(title,queue,'',kind='F',permission=perm);execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{child})')
 execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{queue})')
 key=ensure_menu('消息任务',parent,'messages','oms/messaging/MessageTasks',permission='oms:message:query',order=8)
 for title,perm in [('查询','oms:message:query'),('重试','oms:message:retry')]:
  child=ensure_menu(title,key,'',kind='F',permission=perm);execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{child})')
 execute(f'INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,{key})')
 for title,method in [('渠道消息任务补偿扫描','channel'),('订单消息任务补偿扫描','order')]:
  target='messageDeliveryTask.'+method+'()'
  if execute("SELECT COUNT(*) FROM sys_job WHERE invoke_target='"+target+"'").strip()=='0':execute("INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by) VALUES ('"+title+"','DEFAULT','"+target+"','0/15 * * * * ?','2','1','0','admin')")
 execute("UPDATE sys_job SET status='0',cron_expression='0/30 * * * * ?' WHERE invoke_target='fulfillmentScanTask.salesDocuments()'")
 print('Message schemas and menu installed; no historical tasks emitted')
if __name__=='__main__':main()
