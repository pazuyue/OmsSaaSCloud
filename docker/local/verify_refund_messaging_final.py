"""Recheck the retained refund MQ acceptance facts after restoring queue policies."""
import json,sys
from pathlib import Path
from verify_goods_workspace_ui import login,request
from test_multi_virtual_shipping import sql
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/refund-messaging';DB='qm_oms_saas_order'
def main():
 case=json.loads((OUT/'case.json').read_text('utf-8'));token=login()
 def api(path):return request(path,token=token)['data']
 assert len(case['checks'])==8,case['checks']
 task=api('/order/refunds/tasks/'+str(case['page_task']));assert task['state']=='COMPLETED' and int(task['synced_count'])==51
 ch=str(case['channel_id']);scope=' WHERE company_code=0x514d AND channel_id='+ch
 assert sql('SELECT COUNT(*) FROM oms_platform_refund'+scope,DB)=='53'
 assert sql('SELECT COUNT(*) FROM oms_refund'+scope,DB)=='52'
 assert sql('SELECT COUNT(*) FROM oms_order'+scope,DB)=='1'
 assert sql("SELECT COUNT(*) FROM oms_refund"+scope+" AND (review_state<>'PENDING' OR funds_state<>'NONE' OR goods_action<>'UNKNOWN')",DB)=='0'
 assert sql('SELECT COUNT(*) FROM oms_refund_operation o JOIN oms_refund r ON r.company_code=o.company_code AND r.id=o.refund_id WHERE r.company_code=0x514d AND r.channel_id='+ch,DB)=='0'
 queues=api('/system/mqQueues')['queues'];assert len(queues)==7 and all(q['effective_state']=='RUNNING' for q in queues),[(q['business_type'],q['effective_state']) for q in queues]
 for kind in ['REFUND_PULL','REFUND_CONVERT']:
  queue=next(q for q in queues if q['business_type']==kind);assert int(queue['actual_consumers'])==1
  assert int(sql("SELECT COUNT(*) FROM oms_message_outbox"+scope.replace(' AND channel_id='+ch,'')+" AND message_type='"+kind+"' AND business_id IN (SELECT CAST(id AS CHAR) FROM "+('oms_refund_sync' if kind=='REFUND_PULL' else 'oms_platform_refund')+scope+") AND execution_mode='MQ' AND process_state='SUCCESS'",DB))>0
 case.update({'regression_tests':150,'new_refund_queue_tests':16,'platform_refunds':53,'business_refunds':52,'business_orders':1,'queues':[{k:q[k] for k in ['business_type','effective_state','actual_consumers']} for q in queues]})
 (OUT/'acceptance.json').write_text(json.dumps(case,ensure_ascii=False,indent=2),encoding='utf-8')
 print('PASS retained refund facts, no money/goods execution, seven queues RUNNING; outputs/refund-messaging/acceptance.json',flush=True)
if __name__=='__main__':main()
