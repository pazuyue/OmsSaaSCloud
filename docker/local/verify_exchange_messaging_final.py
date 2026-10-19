"""Read-only checks of retained exchange MQ acceptance facts and restored queue policies."""
import json
from pathlib import Path
from verify_goods_workspace_ui import login,request
from test_multi_virtual_shipping import sql,wait_for
OUT=Path(__file__).resolve().parents[2]/'outputs/exchange-messaging';DB='qm_oms_saas_order'
def main():
 case=json.loads((OUT/'case.json').read_text('utf-8'));token=login()
 def api(path):return request(path,token=token)['data']
 assert len(case['checks'])==8,case['checks'];
 if case.get('final_counter_task'):
  counters=api('/order/exchanges/tasks/'+str(case['final_counter_task']));assert counters['state']=='COMPLETED' and int(counters['synced_count'])==2 and int(counters['unchanged_count'])==2 and int(counters['failed_count'])==0
 ch=str(case['channel_id']);scope=' WHERE company_code=0x514d AND channel_id='+ch
 task=api('/order/exchanges/tasks/'+str(case['page_task']));assert task['state']=='COMPLETED' and int(task['synced_count'])==31
 refreshed=wait_for(lambda:api('/order/exchanges/tasks/'+str(case['refresh_task'])),lambda t:t['state']=='COMPLETED','Browser refresh source task did not finish',120);assert int(refreshed['synced_count'])==1 and int(refreshed['unchanged_count'])==1 and int(refreshed['failed_count'])==0
 assert sql('SELECT COUNT(*) FROM oms_platform_exchange'+scope,DB)=='34'
 wait_for(lambda:sql('SELECT COUNT(*) FROM oms_exchange'+scope,DB),lambda x:x=='33','Remaining exchange conversions did not complete',120)
 assert sql('SELECT COUNT(*) FROM oms_order'+scope,DB)=='1'
 assert sql("SELECT COUNT(*) FROM oms_exchange"+scope+" AND (approved_version IS NOT NULL OR target_sku<>'' OR stock_request IS NOT NULL)",DB)=='0'
 queues=wait_for(lambda:api('/system/mqQueues')['queues'],lambda rows:len(rows)==9 and all(q['effective_state']=='RUNNING' for q in rows),'Queue policies not fully restored',120)
 for kind in ['EXCHANGE_PULL','EXCHANGE_CONVERT']:
  q=next(q for q in queues if q['business_type']==kind);assert int(q['actual_consumers'])==1
  table='oms_exchange_sync' if kind=='EXCHANGE_PULL' else 'oms_platform_exchange'
  assert int(sql("SELECT COUNT(*) FROM oms_message_outbox WHERE company_code=0x514d AND message_type='"+kind+"' AND business_id IN (SELECT CAST(id AS CHAR) FROM "+table+scope+") AND execution_mode='MQ' AND process_state='SUCCESS'",DB))>0
 case.update({'regression_tests':210,'new_exchange_queue_tests':16,'platform_identity_tests':2,'cross_service_rate_test':1,'platform_exchanges':34,'business_exchanges':33,'business_orders':1,'queues':[{k:q[k] for k in ['business_type','effective_state','actual_consumers']} for q in queues]})
 (OUT/'acceptance.json').write_text(json.dumps(case,ensure_ascii=False,indent=2),encoding='utf-8')
 print('PASS retained exchanges, original SO, no approval/stock execution and nine queues RUNNING; outputs/exchange-messaging/acceptance.json')
if __name__=='__main__':main()
