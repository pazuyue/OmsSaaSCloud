"""Retained queue management acceptance, including two real channel instances.
Only simulated catalog data is created. Pause policies, broker and replica are restored in finally.
"""
import json,sys,time,subprocess,urllib.request,base64
from pathlib import Path
from datetime import datetime
from verify_goods_workspace_ui import login,request
from test_multi_virtual_shipping import sql,quote,wait_for
OUT=Path(__file__).resolve().parents[2]/'outputs/queue-management'
REPLICA='oms-local-channel-queue-test'
def docker(*args,timeout=90,check=True):
 r=subprocess.run(['wsl.exe','--','docker',*args],capture_output=True,timeout=timeout)
 if check and r.returncode:raise RuntimeError('Local container operation failed: '+args[0])
 return r.stdout.decode('utf-8').strip()
def health(name):return docker('inspect','--format','{{.State.Health.Status}}',name)
def wait_health(name):wait_for(lambda:health(name),lambda s:s=='healthy','Service not healthy: '+name,180)
def main():
 OUT.mkdir(parents=True,exist_ok=True);source=json.loads((OUT.parent/'channel-automation/case.json').read_text('utf-8'));case={'stamp':datetime.now().strftime('%Y%m%d%H%M%S'),'channel_id':source['channel_id'],'sku':source['sku'],'checks':[]};replica_owned=False
 for name in ['oms-local-system','oms-local-channel','oms-local-order']:wait_health(name)
 def fresh_login():
  try:return login()
  except (AssertionError,OSError):return None
 token=wait_for(fresh_login,lambda t:t is not None,'Authentication service not ready',150)
 def save(): (OUT/'case.json').write_text(json.dumps(case,ensure_ascii=False,indent=2),encoding='utf-8')
 def api(path,body=None,method=None):return request(path,token=token,payload=body,method=method or ('POST' if body is not None else 'GET'))['data']
 def queue(kind='PRODUCT_PULL'):return api('/system/mqQueues/'+kind)
 def edit(kind,changes,reason):r=queue(kind);r.update(changes);r['reason']=reason;return api('/system/mqQueues/'+kind,r,'PUT')
 def state(kind,wanted,consumers=None):return wait_for(lambda:queue(kind),lambda r:r['effective_state']==wanted and (consumers is None or r['actual_consumers']==consumers),'Queue state not applied: '+kind+' '+wanted,180)
 def checked(name):case['checks'].append(name);save();print('PASS '+name,flush=True)
 wait_health('oms-local-system');wait_health('oms-local-channel');wait_health('oms-local-order');original={r['business_type']:r for r in api('/system/mqQueues')['queues']};save()
 try:
  existing=json.loads(docker('inspect',REPLICA,check=False) or '[]')
  if existing:
   info=existing[0];assert info['Config']['Labels'].get('com.docker.compose.oneoff')=='True' and info['Config']['Labels'].get('com.docker.compose.service')=='channel';replica_owned=True;docker('start',REPLICA)
  else:docker('compose','-f','docker/local/compose.yaml','run','--no-deps','-d','--name',REPLICA,'channel');replica_owned=True
  wait_health(REPLICA);wait_for(lambda:queue(),lambda r:len([i for i in r['instances'] if i['online'] and not i['retired']])==2,'Two consumer instances not registered',150)
  edit('PRODUCT_PULL',{'desired_mode':'RUNNING','concurrency':3,'prefetch':2},'[验收] 两实例统一调参');r=state('PRODUCT_PULL','RUNNING',6);assert all(i['applied_version']==r['revision'] for i in r['instances'] if i['online']);checked('both instances apply concurrency and prefetch; six actual consumers')
  edit('PRODUCT_PULL',{'desired_mode':'PAUSED'},'[验收] 同时暂停消息与数据库补偿');state('PRODUCT_PULL','PAUSED',0)
  ch=case['channel_id'];item='94'+case['stamp']+'1';payload={'num_iid':item,'title':'[队列管理验收] 暂停后继续','approve_status':'onsale','modified':'2026-10-19 18:00:00','num':2,'skus':{'sku':[{'sku_id':'93'+case['stamp']+'1','outer_id':case['sku'],'properties':'1:1','quantity':2}]}}
  assert api('/channel/platform/shops/'+str(ch))['simulation'] is True
  sql("INSERT INTO channel_product_sim(company_code,channel_id,item_id,payload) VALUES ('QM',"+str(ch)+","+quote(item)+","+quote(json.dumps(payload,ensure_ascii=False))+")",'qm_oms_saas_channel')
  wait_for(lambda:api('/channel/platformProducts/pull/'+str(ch)+'/tasks'),lambda rows:not any(t['state'] in ['PENDING','RUNNING'] for t in rows),'Shop has active task',120)
  task=api('/channel/platformProducts/pull/'+str(ch),{'mode':'IDS','item_ids':[item]})['id'];case['task_id']=task;save()
  sql("UPDATE oms_message_outbox SET next_process_at=DATE_SUB(NOW(),INTERVAL 130 SECOND) WHERE company_code='QM' AND business_number='PRODUCTPULL"+str(task)+"'",'qm_oms_saas_channel')
  for container in ['oms-local-channel',REPLICA]:
   response=json.loads(docker('exec',container,'curl','-fsS','-X','POST','-H','from-source: inner','http://localhost:9304/messages/internal/compensate'));assert response['code']==200
  time.sleep(8);assert api('/channel/platformProducts/tasks/'+str(task))['state']=='PENDING';assert sql("SELECT consume_attempts FROM oms_message_outbox WHERE company_code='QM' AND business_number='PRODUCTPULL"+str(task)+"'",'qm_oms_saas_channel')=='0';checked('pause prevents both MQ and direct compensation on both instances')
  docker('restart','--time','60','oms-local-channel',timeout=100);wait_health('oms-local-channel');state('PRODUCT_PULL','PAUSED',0);assert api('/channel/platformProducts/tasks/'+str(task))['state']=='PENDING';checked('service restart preserves paused configuration and durable pending task')
  edit('PRODUCT_PULL',{'desired_mode':'RUNNING'},'[验收] 恢复原待办');state('PRODUCT_PULL','RUNNING',6)
  messages=wait_for(lambda:api('/channel/messages?keyword=PRODUCTPULL'+str(task))['rows'],lambda rows:rows and rows[0]['process_state']=='SUCCESS','Pending task did not resume',180);case['event_id']=messages[0]['event_id'];case['business_number']='PRODUCTPULL'+str(task);assert sql("SELECT COUNT(*) FROM oms_message_attempt WHERE company_code='QM' AND event_id="+quote(case['event_id'])+" AND stage='PROCESS' AND result='SUCCESS'",'qm_oms_saas_channel')=='1';checked('resume completes original event once with two competing consumers')
  wait_for(lambda:api('/system/mqQueues')['queues'],lambda rows:all(r['broker']['fresh'] for r in rows),'Broker snapshots not fresh',90)
  docker('stop','--time','30','oms-local-rabbitmq');
  wait_for(lambda:queue(),lambda r:not r['broker']['fresh'] and r['broker'].get('sampled_at'),'Monitoring failure did not preserve stale sample',60)
  for container in ['oms-local-channel','oms-local-order']:assert health(container)=='healthy'
  wait_for(lambda:queue()['alerts'],lambda alerts:any(a['rule_code']=='BROKER_UNAVAILABLE' and a['state']=='FIRING' for a in alerts),'Sustained broker failure did not raise alert',120);checked('broker outage keeps services healthy, preserves cached metrics and raises sustained alert')
  docker('start','oms-local-rabbitmq');wait_health('oms-local-rabbitmq');wait_for(lambda:queue(),lambda r:r['broker']['fresh'] and not any(a['rule_code']=='BROKER_UNAVAILABLE' for a in r['alerts']),'Monitoring did not recover',120);checked('broker recovery refreshes metrics and records alert recovery')
  targets=json.load(urllib.request.urlopen('http://127.0.0.1:19090/api/v1/targets',timeout=10))['data']['activeTargets'];wait_for(lambda:json.load(urllib.request.urlopen('http://127.0.0.1:19090/api/v1/targets',timeout=10))['data']['activeTargets'],lambda rows:len(rows)==5 and all(r['health']=='up' for r in rows),'Prometheus targets not healthy',120)
  from local_db import ENV
  header={'Authorization':'Basic '+base64.b64encode((ENV['GRAFANA_ADMIN_USER']+':'+ENV['GRAFANA_ADMIN_PASSWORD']).encode()).decode()};dashboard=json.load(urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:13000/api/dashboards/uid/oms-messaging',headers=header),timeout=15));assert len(dashboard['dashboard']['panels'])>=9;checked('all messaging instances discovered by Prometheus and Grafana dashboard ready')
 finally:
  docker('start','oms-local-rabbitmq',check=False)
  for kind,p in original.items():
   latest=queue(kind);latest.update({k:p[k] for k in ['desired_mode','concurrency','prefetch','retry_limit','retry_base_sec','retry_max_sec','compensate_after_sec','compensate_batch','app_rate','shop_rate','alarm_backlog','alarm_due_sec','alarm_failure_pct']});latest['reason']='[验收] 恢复原配置';api('/system/mqQueues/'+kind,latest,'PUT')
  if replica_owned:docker('stop','--time','60',REPLICA,timeout=100);docker('rm',REPLICA)
  state('PRODUCT_PULL',original['PRODUCT_PULL']['desired_mode'],original['PRODUCT_PULL']['concurrency']);save()
 print('PASS queue management acceptance; retained '+case['business_number'],flush=True)
def monitoring_only():
 case=json.loads((OUT/'case.json').read_text('utf-8'))
 def fresh_login():
  try:return login()
  except (AssertionError,OSError):return None
 token=wait_for(fresh_login,lambda t:t is not None,'Authentication service not ready',150)
 def api(path,body=None):return request(path,token=token,payload=body,method='POST' if body is not None else None)['data']
 def targets():return json.load(urllib.request.urlopen('http://127.0.0.1:19090/api/v1/targets',timeout=10))['data']['activeTargets']
 wait_for(targets,lambda rows:len(rows)==4 and all(r['health']=='up' for r in rows),'Baseline monitoring targets not ready',180)
 created=False
 try:
  existing=json.loads(docker('inspect',REPLICA,check=False) or '[]');assert not existing,'Unexpected acceptance replica already present'
  docker('compose','-f','docker/local/compose.yaml','run','--no-deps','-d','--name',REPLICA,'channel');created=True;wait_health(REPLICA)
  rows=wait_for(targets,lambda rows:len(rows)==5 and all(r['health']=='up' for r in rows),'Replica was not automatically discovered',150);assert len([r for r in rows if r['labels'].get('application')=='oms-channel'])==2
  print('PASS Prometheus automatically discovers both channel instances',flush=True)
  ch=case['channel_id'];stamp=datetime.now().strftime('%Y%m%d%H%M%S');item='94'+stamp+'2';payload={'num_iid':item,'title':'[监控验收] 实例指标','approve_status':'onsale','modified':'2026-10-19 19:00:00','num':1,'outer_id':case['sku']}
  sql("INSERT INTO channel_product_sim(company_code,channel_id,item_id,payload) VALUES ('QM',"+str(ch)+","+quote(item)+","+quote(json.dumps(payload,ensure_ascii=False))+")",'qm_oms_saas_channel')
  wait_for(lambda:api('/channel/platformProducts/pull/'+str(ch)+'/tasks'),lambda rows:not any(t['state'] in ['PENDING','RUNNING'] for t in rows),'Shop still processing',120)
  task=api('/channel/platformProducts/pull/'+str(ch),{'mode':'IDS','item_ids':[item]})['id'];case['monitor_task_id']=task
  wait_for(lambda:api('/channel/platformProducts/tasks/'+str(task)),lambda r:r['state']=='SUCCESS','Monitoring sample task did not complete',120)
  query='http://127.0.0.1:19090/api/v1/query?query='+__import__('urllib.parse',fromlist=['quote']).quote('sum(oms_message_processing_seconds_count{business_type="PRODUCT_PULL"})')
  wait_for(lambda:json.load(urllib.request.urlopen(query))['data']['result'],lambda r:r and float(r[0]['value'][1])>0,'Business timer metrics missing',90)
  from local_db import ENV
  h={'Authorization':'Basic '+base64.b64encode((ENV['GRAFANA_ADMIN_USER']+':'+ENV['GRAFANA_ADMIN_PASSWORD']).encode()).decode()};dashboard=json.load(urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:13000/api/dashboards/uid/oms-messaging',headers=h)));assert len(dashboard['dashboard']['panels'])>=9
  case['checks'].extend(['Prometheus discovers all service replicas','business processing timer collected','nine-panel Grafana dashboard provisioned']);print('PASS business metrics and nine-panel Grafana dashboard',flush=True)
 finally:
  if created:docker('stop','--time','60',REPLICA,timeout=100);docker('rm',REPLICA)
  (OUT/'case.json').write_text(json.dumps(case,ensure_ascii=False,indent=2),encoding='utf-8')
 wait_for(targets,lambda rows:len(rows)==4 and all(r['health']=='up' for r in rows),'Stopped replica was not removed from monitoring',150);print('PASS stopped replica removed; all monitoring targets healthy',flush=True)
if __name__=='__main__':
 if '--monitor-only' in sys.argv:monitoring_only()
 else:main()
