"""Retained local RabbitMQ acceptance. Only simulated product fixtures/read-model sync are used.
Always restores stopped local services in finally. No platform stock writes or WMS stock commands.
"""
import json,sys,time,subprocess,urllib.parse
from pathlib import Path
from datetime import datetime
from verify_goods_workspace_ui import login,request,BASE
from test_multi_virtual_shipping import sql,quote,wait_for
OUT=Path(__file__).resolve().parents[2]/'outputs/messaging'
CH='qm_oms_saas_channel';ORDER='qm_oms_saas_order'

def docker(*args):
 r=subprocess.run(['wsl.exe','--','docker',*args],capture_output=True,timeout=60)
 if r.returncode:raise RuntimeError('Local container operation failed: '+args[0])
 return r.stdout.decode('utf-8').strip()
def management(action,event=None):
 script="import sys,json,base64,urllib.request\nsys.path.insert(0,'docker/local')\nfrom local_db import ENV\nheaders={'Authorization':'Basic '+base64.b64encode((ENV['RABBITMQ_USER']+':'+ENV['RABBITMQ_PASSWORD']).encode()).decode(),'Content-Type':'application/json'}\n"
 if action=='publish':
  body={'properties':{'delivery_mode':2,'content_type':'application/json','message_id':event['event_id']},'routing_key':'PRODUCT_PULL','payload':json.dumps(event),'payload_encoding':'string'}
  script+="body="+repr(body)+"\nr=urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:15672/api/exchanges/oms/oms.work.v1/publish',data=json.dumps(body).encode(),headers=headers),timeout=10)\nprint(r.read().decode())"
 else:script+="r=urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:15672/api/queues/oms/oms.channel.product-pull.v1',headers=headers),timeout=10)\na=json.load(r)\nprint(json.dumps({'ready':a.get('messages_ready',0),'consumers':a.get('consumers',0)}))"
 r=subprocess.run(['wsl.exe','--','python3','-'],input=script.encode(),capture_output=True,timeout=20)
 if r.returncode:raise RuntimeError('Local broker management operation failed')
 return json.loads(r.stdout)
def main():
 OUT.mkdir(parents=True,exist_ok=True);manifest=OUT/'case.json';case=json.loads(manifest.read_text('utf-8')) if manifest.exists() else {'stamp':datetime.now().strftime('%Y%m%d%H%M%S')}
 def save():manifest.write_text(json.dumps(case,ensure_ascii=False,indent=2),encoding='utf-8')
 source=json.loads((OUT.parent/'channel-automation/case.json').read_text('utf-8'));ch=source['channel_id'];case.update(channel_id=ch,sku=source['sku']);save();token=login()
 def api(path,body=None):return request(path,token=token,payload=body,method='POST' if body is not None else None).get('data')
 def messages(task):return api('/channel/messages?keyword=PRODUCTPULL'+str(task))['rows']
 def current(task):return max(messages(task),key=lambda r:r['id'])
 def await_state(task,state,seconds=120):return wait_for(lambda:current(task),lambda r:r['process_state']==state,'Message did not reach '+state,seconds)
 def seed(i):
  item='97'+case['stamp']+str(i);payload={'num_iid':item,'title':'[MQ验收] '+str(i),'approve_status':'onsale','modified':'2026-10-19 16:00:00','num':2,'skus':{'sku':[{'sku_id':'98'+case['stamp']+str(i),'outer_id':case['sku'],'properties':'1:1','properties_name':'消息验收规格','quantity':2}]}}
  sql("INSERT INTO channel_product_sim(company_code,channel_id,item_id,payload) VALUES ('QM',"+str(ch)+","+quote(item)+","+quote(json.dumps(payload,ensure_ascii=False))+") ON DUPLICATE KEY UPDATE payload=VALUES(payload)",CH);return item
 def pull(item):
  wait_for(lambda:api('/channel/platformProducts/pull/'+str(ch)+'/tasks'),lambda rows:not any(r['state'] in ['PENDING','RUNNING'] for r in rows),'Shop still has active pull',150)
  return api('/channel/platformProducts/pull/'+str(ch),{'mode':'IDS','item_ids':[item]})['id']
 if not case.get('normal_task'):case['normal_task']=pull(seed(1));save()
 r=await_state(case['normal_task'],'SUCCESS');assert r['execution_mode']=='MQ';case['normal_event']=r['event_id'];save();print('PASS real publisher confirm + manual consumer ack',flush=True)
 # Explicit failed product task, original audit retained and a new work version produced.
 if not case.get('failed_task'):case['failed_task']=pull('97'+case['stamp']+'2');save()
 task=case['failed_task'];r=current(task)
 if r['business_version']==1:
  r=await_state(task,'FAILED');seed(2);api('/channel/messages/'+r['event_id']+'/retry',{'reason':'[MQ验收] 已补齐模拟平台商品，按原任务重试'})
 r=await_state(task,'SUCCESS');assert r['business_version']>=2;assert any(x['process_state']=='FAILED' for x in messages(task));case['retry_event']=r['event_id'];save();print('PASS manual retry creates a new version and retains original failure',flush=True)
 try:
  if not case.get('outage_done'):
   docker('stop','oms-local-rabbitmq');case['outage_task']=pull(seed(3));save();task=case['outage_task']
   wait_for(lambda:current(task),lambda r:r['publish_state']=='UNCERTAIN','Outage did not retain delivery',45)
   for name in ['channel','order']:assert docker('inspect','--format','{{.State.Health.Status}}','oms-local-'+name)=='healthy'
   r=await_state(task,'SUCCESS',200);assert r['execution_mode']=='COMPENSATION' and r['publish_state']=='SKIPPED';case['outage_event']=r['event_id'];case['outage_done']=True;save();print('PASS broker stopped: business services healthy; durable compensation completed',flush=True)
  if not case.get('restart_done'):
   docker('stop','oms-local-rabbitmq');case['restart_task']=pull(seed(4));save();task=case['restart_task'];r=current(task)
   docker('stop','oms-local-channel');docker('start','oms-local-rabbitmq');wait_for(lambda:docker('inspect','--format','{{.State.Health.Status}}','oms-local-rabbitmq'),lambda s:s=='healthy','Broker failed to recover',120)
   envelope={k:r[k] for k in ['event_id','schema_version','message_type','company_code','business_id','business_version','trace_id','service_code','create_time']};assert management('publish',envelope)['routed']
   wait_for(lambda:management('queue'),lambda q:q['ready']>=1 and q['consumers']==0,'Durable queue not holding work',30)
   docker('restart','oms-local-rabbitmq');wait_for(lambda:docker('inspect','--format','{{.State.Health.Status}}','oms-local-rabbitmq'),lambda s:s=='healthy','Broker restart failed',120)
   wait_for(lambda:management('queue'),lambda q:q['ready']>=1,'Persistent message lost after broker restart',45)
   docker('start','oms-local-channel');wait_for(lambda:docker('inspect','--format','{{.State.Health.Status}}','oms-local-channel'),lambda s:s=='healthy','Channel restart failed',150)
   r=await_state(task,'SUCCESS',120);assert r['execution_mode']=='MQ';before=sql("SELECT COUNT(*) FROM channel_interaction_log WHERE company_code='QM' AND operation_id='PRODUCTPULL"+str(task)+"' AND action='PRODUCT_DETAIL'",CH)
   assert management('publish',envelope)['routed'];time.sleep(4);after=sql("SELECT COUNT(*) FROM channel_interaction_log WHERE company_code='QM' AND operation_id='PRODUCTPULL"+str(task)+"' AND action='PRODUCT_DETAIL'",CH);assert before==after
   case['restart_event']=r['event_id'];case['restart_done']=True;save();print('PASS persistent queue survives broker restart; completed duplicate does not rerun API',flush=True)
 finally:
  docker('start','oms-local-rabbitmq');docker('start','oms-local-channel')
 # Read-model delivery only: dirty an existing retained simulation document and scan it.
 prior=json.loads((OUT.parent/'immediate-fulfillment/case.json').read_text('utf-8'));shipment=prior.get('warehouses',prior.get('stores',[]))[0] if prior.get('warehouses',prior.get('stores')) else None
 if not shipment:shipment=next(v[0] for v in prior.values() if isinstance(v,list) and v and isinstance(v[0],dict) and 'shipment_id' in v[0])
 sid=shipment['shipment_id'];sn=shipment['shipment_sn'];case['sales_document']=sn;save()
 stock_before=sql("SELECT total_stock,available_stock,reserved_stock FROM oms_inventory WHERE company_code='QM' AND sku_sn="+quote(prior['sku']),'qm_oms_saas_inventory')
 sql("UPDATE oms_order_sales_document_sync SET dirty_version=dirty_version+1,next_attempt=NOW() WHERE company_code='QM' AND shipment_id="+str(sid),ORDER)
 wait_for(lambda:api('/order/messages?keyword='+sn)['rows'],lambda rows:rows and rows[0]['process_state']=='SUCCESS','Sales read-model message not complete',180)
 stock_after=sql("SELECT total_stock,available_stock,reserved_stock FROM oms_inventory WHERE company_code='QM' AND sku_sn="+quote(prior['sku']),'qm_oms_saas_inventory');assert stock_before==stock_after;print('PASS sales projection notification completed without stock mutation',flush=True)
 case['checks']=['publisher confirms','manual ack','atomic outbox rollback (unit)','manual retry audit','outage compensation','business services stay healthy','persistent quorum queue restart','service restart','duplicate message no duplicate platform call','sales projection no stock mutation'];save()
 print('PASS messaging acceptance retained at outputs/messaging/case.json',flush=True)
if __name__=='__main__':main()
