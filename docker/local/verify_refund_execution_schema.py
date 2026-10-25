"""Read-only verification of refund schema comments, original-source indexes and queue budget."""
import json,re
from local_db import execute, ROOT

ORDER='qm_oms_saas_order'
CHANNEL='qm_oms_saas_channel'

def quoted(value):
    return "'"+value.replace("'","''")+"'"

def main():
    tables={ORDER:['oms_refund_goods_task','oms_refund_flow_policy','oms_refund_flow_task','oms_refund_api_cursor','oms_refund_api_item'],CHANNEL:['channel_refund_access','channel_refund_sim','channel_refund_command']}
    checks=[]
    for database,names in tables.items():
        for table in names:
            fields=execute("SELECT COLUMN_NAME,COLUMN_COMMENT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME="+quoted(table),database).splitlines()
            assert fields and all(len(row.split('\t',1))==2 and re.search(r'[\u4e00-\u9fff]',row.split('\t',1)[1]) and '???' not in row for row in fields),table+' has undocumented fields'
            comment=execute("SELECT TABLE_COMMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME="+quoted(table),database).strip()
            assert comment,table+' has no table comment'
            checks.append({'table':table,'documented_columns':len(fields),'comment':comment})
    added={ORDER:{'oms_refund':['initial_fulfillment_stage','initial_fulfillment_snapshot','platform_context','platform_checked_at'],'oms_refund_operation':['approved_hash','policy_revision','request_json','response_json','lease_until'],'oms_order_line':['refund_block_reason'],'oms_platform_refund':['source_kind'],'oms_platform_refund_snapshot':['source_kind']},CHANNEL:{'channel_oauth_state':['grant_role'],'channel_refund_access':['payment_enabled','ignore_code','payment_expires_at','payment_sub_id','payment_app_id','payment_seller_id']}}
    for database,tables_by_name in added.items():
        for table,columns in tables_by_name.items():
            count=int(execute("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME="+quoted(table)+" AND COLUMN_NAME IN ("+','.join(map(quoted,columns))+") AND COLUMN_COMMENT<>''",database).strip())
            assert count==len(columns),(table,count,columns)
    indexes={}
    for table,index in [('oms_order_shipment_line','refund_original_line'),('oms_refund_goods_task','return_source'),('oms_refund_operation','execution_scope'),('oms_refund_api_item','shop_work')]:
        value=execute("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX),MAX(INDEX_COMMENT) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME="+quoted(table)+" AND INDEX_NAME="+quoted(index),ORDER).strip()
        assert value and 'NULL' not in value,(table,index)
        indexes[table+'.'+index]=value
    line=execute("SELECT order_line_id FROM oms_order_shipment_line WHERE company_code='QM' ORDER BY id DESC LIMIT 1",ORDER).strip()
    explain=''
    if line:
        assert line.isdigit()
        explain=execute("EXPLAIN SELECT shipped_quantity FROM oms_order_shipment_line WHERE company_code='QM' AND order_line_id="+line,ORDER).strip()
        assert 'refund_original_line' in explain,explain
    queues=execute("SELECT business_type,concurrency,desired_mode FROM sys_mq_queue_policy ORDER BY business_type").strip()
    order_total=sum(int(row.split('\t')[1]) for row in queues.splitlines() if row.split('\t')[0] not in ['PRODUCT_PULL','PRODUCT_BIND'])
    assert order_total<=16,('order queue budget exceeded',order_total)
    assert all(kind in queues for kind in ['REFUND_GOODS','REFUND_FLOW','REFUND_PLATFORM','REFUND_API_PULL','REFUND_API_DETAIL'])
    report={'tables':checks,'indexes':indexes,'original_source_explain':explain,'order_queue_concurrency':order_total,'queues':queues,'business_backfill':False}
    target=ROOT/'outputs/refund-execution/schema.json';target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    print('PASS refund table/column/index comments, original-source index and queue budget; report:',target.relative_to(ROOT))

if __name__=='__main__':main()
