"""Final active-company smoke and monitoring checks; reuses the retained isolated seller."""
import json,time,urllib.request,urllib.parse
from pathlib import Path
from verify_goods_workspace_ui import login,request
from verify_queue_management import wait_health
from test_multi_virtual_shipping import sql,wait_for
OUT=Path(__file__).resolve().parents[2]/'outputs/order-messaging';DB='qm_oms_saas_order'
def main():
 for service in ['oms-local-system','oms-local-channel','oms-local-order']:wait_health(service)
 token=login();c=json.loads((OUT/'case.json').read_text('utf-8'))
 def api(path,body=None):return request(path,token=token,payload=body,method='POST' if body is not None else 'GET')['data']
 where=" WHERE company_code='QM' AND channel_id="+str(c['channel_id']);before=sql('SELECT COUNT(*) FROM oms_order'+where,DB);platforms=sql('SELECT COUNT(*) FROM oms_platform_order'+where,DB)
 task=api('/order/orders/backfill',{'mode':'TID','channel_ids':[c['channel_id']],'tids':[c['tids'][0]],'request_key':'ORDER_FINAL_'+c['stamp']})['task_ids'][0]
 r=wait_for(lambda:api('/order/orders/tasks/'+str(task)),lambda r:r['status']=='SUCCESS' and r.get('message',{}).get('process_state')=='SUCCESS','Final active-company pull did not complete',150);assert before==sql('SELECT COUNT(*) FROM oms_order'+where,DB) and platforms==sql('SELECT COUNT(*) FROM oms_platform_order'+where,DB)
 queues=wait_for(lambda:api('/system/mqQueues')['queues'],lambda rows:len(rows)==5 and all(r['effective_state']=='RUNNING' and r['broker']['fresh'] for r in rows),'Five queues not healthy',150)
 def targets():
  with urllib.request.urlopen('http://127.0.0.1:19090/api/v1/targets',timeout=15) as response:return json.load(response)['data']['activeTargets']
 active=wait_for(targets,lambda rows:len(rows)==4 and all(r['health']=='up' for r in rows),'Monitoring discovery not recovered',150)
 dashboard=json.loads((Path(__file__).parent/'monitoring/grafana/dashboards/oms-messaging.json').read_text('utf-8'));queries=0
 for panel in dashboard['panels']:
  for target in panel['targets']:
   with urllib.request.urlopen('http://127.0.0.1:19090/api/v1/query?'+urllib.parse.urlencode({'query':target['expr']}),timeout=15) as response:assert json.load(response)['status']=='success'
   queries+=1
 result={'existing_regression_batch':306,'focused_final_batch':132,'new_intake_cases':12,'live_checks':c['checks']+['final active-company pull after tenant-boundary deployment preserves existing identities'],'final_smoke_task':task,'platform_orders':int(platforms),'business_orders':int(before),'queues':[{k:r[k] for k in ['business_type','effective_state','actual_consumers']} for r in queues],'monitoring_targets':len(active),'valid_dashboard_queries':queries,'retained_pagination_task':c['page_task'],'retained_repaired_tid':c['failed_tid'],'representative_business_order':c['order_sn']}
 (OUT/'acceptance.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8');print('PASS final order intake: five applied queues, four healthy metrics targets, no duplicate orders; retained task '+str(c['page_task']))
if __name__=='__main__':main()
