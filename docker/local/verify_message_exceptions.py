"""Local simulation acceptance: rate wait, pre-entry configuration repair, retry chain, real TTL dead letter + compensation."""
import json,time,hashlib,subprocess,base64,urllib.request,urllib.parse
from pathlib import Path
from datetime import datetime
from verify_goods_workspace_ui import login,request,BASE
from test_multi_virtual_shipping import sql,quote,wait_for
from verify_queue_management import docker,wait_health
OUT=Path(__file__).resolve().parents[2]/'outputs/message-exceptions';CH='qm_oms_saas_channel'

def main():
 OUT.mkdir(parents=True,exist_ok=True);case={'stamp':datetime.now().strftime('%Y%m%d%H%M%S'),'checks':[]};token=None
 def save(): (OUT/'case.json').write_text(json.dumps(case,ensure_ascii=False,indent=2),encoding='utf-8')
 def checked(v):case['checks'].append(v);save();print('PASS '+v,flush=True)
 def api(path,body=None,method=None):
  response=request(path,token=token,payload=body,method=method or ('POST' if body is not None else 'GET'));return response.get('data',response)
 def policy():return api('/system/mqQueues/PRODUCT_PULL')
 def edit(changes):r=policy();r.update(changes);r['reason']='[异常闭环验收] '+case['stamp'];return api('/system/mqQueues/PRODUCT_PULL',r,'PUT')
 def mode(value):edit({'desired_mode':value});return wait_for(policy,lambda r:r['effective_state']==value,'Queue mode not applied',300)
 def current(task):return api('/channel/messages?keyword=PRODUCTPULL'+str(task))['rows'][0]
 def await_task(task,state):return wait_for(lambda:current(task),lambda r:r['process_state']==state,'Task did not reach '+state,120)
 def seed(i):
  item='93'+case['stamp']+str(i);payload={'num_iid':item,'title':'[异常闭环验收] '+str(i),'approve_status':'onsale','modified':datetime.now().strftime('%Y-%m-%d %H:%M:%S'),'num':2,'skus':{'sku':[{'sku_id':'92'+case['stamp']+str(i),'outer_id':case['sku'],'properties':'1:1','properties_name':'模拟规格','quantity':2}]}}
  sql("INSERT INTO channel_product_sim(company_code,channel_id,item_id,payload) VALUES ('QM',"+str(case['channel_id'])+","+quote(item)+","+quote(json.dumps(payload,ensure_ascii=False))+") ON DUPLICATE KEY UPDATE payload=VALUES(payload)",CH);return item
 def pull(item):return api('/channel/platformProducts/pull/'+str(case['channel_id']),{'mode':'IDS','item_ids':[item]})['id']
 def shot(event,name,expected=None):
  from playwright.sync_api import sync_playwright,expect
  with sync_playwright() as p:
   b=p.chromium.launch(headless=True);ctx=b.new_context(viewport={'width':1740,'height':1150});ctx.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=ctx.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)));page.goto(BASE+'/monitor/messages?service=channel&type=PRODUCT_PULL&event='+event,wait_until='networkidle');expect(page.locator('.messages .el-table__body-wrapper').first.get_by_text(event,exact=True)).to_be_visible();page.locator('.el-table__fixed-right').get_by_role('button',name='详情 / 链路',exact=True).first.click();drawer=page.locator('.el-drawer:visible');expect(drawer.get_by_text('同业务通知链路',exact=True)).to_be_visible();
   if expected:expect(drawer.get_by_text(expected,exact=False).first).to_be_visible()
   page.screenshot(path=str(OUT/name),full_page=True,animations='disabled');assert not errors,errors;b.close()
 for name in ['oms-local-system','oms-local-channel','oms-local-order']:wait_health(name)
 token=login();original=policy();source=json.loads((OUT.parent/'platform-products/case.json').read_text('utf-8'));fixture=json.loads((OUT.parent/'channel-automation/case.json').read_text('utf-8'));case['sku']=fixture['sku'];save()
 try:
  name='[消息异常验收] '+case['stamp'];api('/channel/channel',{'channelName':name,'channelType':'TM','enabled':1,'toChannelEnabled':1,'syncEnabled':0,'mmodelType':1});ch=api('/channel/channel/list?channelName='+urllib.parse.quote(name))['rows'][0]['channelId'];case['channel_id']=ch;save();api('/channel/platform/shops/'+str(ch)+'/binding',{'app_id':source['app_id'],'expected_shop_id':'9988'+str(ch)});api('/channel/platform/shops/'+str(ch)+'/simulate',{});assert api('/channel/platform/shops/'+str(ch))['simulation'] is True;api('/channel/channelSettings/products/'+str(ch));sql("UPDATE channel_product_policy SET auto_pull=0,auto_bind=0 WHERE company_code='QM' AND channel_id="+str(ch),CH)
  mode('RUNNING')
  # Only the new simulated shop's rate slot is held. Shared application quota is untouched.
  key=hashlib.sha256(('SHOP:TM:9988'+str(ch)).encode()).hexdigest();sql("INSERT INTO sys_mq_rate_slot(slot_key,next_at) VALUES ("+quote(key)+",DATE_ADD(NOW(),INTERVAL 30 SECOND)) ON DUPLICATE KEY UPDATE next_at=VALUES(next_at)",'ry-cloud')
  task=pull(seed(1));case['rate_task']=task;r=wait_for(lambda:current(task),lambda r:r['problem_code']=='RATE_LIMIT','Rate wait not visible',40);assert r['business_attempts']==0;case['rate_event']=r['event_id'];save();shot(r['event_id'],'rate-wait.png','调用限流');await_task(task,'SUCCESS');checked('rate wait is visible, consumes no failure budget and resumes automatically')
  # A genuinely missing configuration before business entry, using only this owned task.
  mode('PAUSED');task=pull(seed(2));case['config_task']=task;save();sql("UPDATE channel_product_pull SET channel_id="+str(90000000+task)+" WHERE company_code='QM' AND id="+str(task),CH);edit({'desired_mode':'RUNNING','retry_limit':2,'retry_base_sec':5,'retry_max_sec':5});r=wait_for(lambda:current(task),lambda r:r['problem_code']=='CONFIGURATION','Configuration failure not classified',60);case['config_event']=r['event_id'];save();shot(r['event_id'],'configuration-failure.png','原任务所需的店铺或业务配置缺失');r=await_task(task,'FAILED');assert r['problem_code']=='RETRY_EXHAUSTED' and '配置缺失' in r['last_error'];sql("UPDATE channel_product_pull SET channel_id="+str(ch)+" WHERE company_code='QM' AND id="+str(task),CH);api('/channel/messages/'+r['event_id']+'/retry',{'reason':'[验收] 已恢复此模拟任务的店铺关联配置'});latest=await_task(task,'SUCCESS');detail=api('/channel/messages/'+r['event_id']);assert detail['chain'][0]['event_id']==latest['event_id'] and detail['chain'][0]['parent_event_id']==r['event_id'];case['config_retry_event']=latest['event_id'];save();shot(r['event_id'],'retry-chain.png','人工重试');checked('configuration error exhausts safely, repair retries pending business and retains parent/latest chain')
  # The normal business FAILED/PARTIAL retry path still advances work_version.
  missing='93'+case['stamp']+'3';task=pull(missing);r=await_task(task,'FAILED');case['business_failure_event']=r['event_id'];seed(3);api('/channel/messages/'+r['event_id']+'/retry',{'reason':'[验收] 已补齐模拟平台商品'});latest=await_task(task,'SUCCESS');assert latest['business_version']>r['business_version'];assert api('/channel/messages/'+r['event_id'])['chain'][0]['parent_event_id']==r['event_id'];checked('business failure repair preserves original audit and creates a new work version')
  # Real broker expiry -> DLX -> quarantine, followed by the original business compensation.
  mode('PAUSED');task=pull(seed(4));r=current(task);case['dead_task']=task;case['dead_event']=r['event_id'];save();envelope={k:r[k] for k in ['event_id','schema_version','message_type','company_code','business_id','business_version','trace_id','service_code','create_time']}
  body={'properties':{'delivery_mode':2,'expiration':'1000','content_type':'application/json','message_id':r['event_id']},'routing_key':'PRODUCT_PULL','payload':json.dumps(envelope),'payload_encoding':'string'}
  # Run inside WSL to use the same local broker endpoint as the deployment.
  script="import sys,json,base64,urllib.request\nsys.path.insert(0,'docker/local')\nfrom local_db import ENV\nheaders={'Authorization':'Basic '+base64.b64encode((ENV['RABBITMQ_USER']+':'+ENV['RABBITMQ_PASSWORD']).encode()).decode(),'Content-Type':'application/json'}\nbody="+repr(body)+"\nr=urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:15672/api/exchanges/oms/oms.work.v1/publish',data=json.dumps(body).encode(),headers=headers),timeout=10)\nassert json.load(r)['routed']"
  result=subprocess.run(['wsl.exe','--','python3','-'],input=script.encode(),capture_output=True,timeout=25);assert result.returncode==0,'TTL fixture publish failed'
  def dead():return next((x for x in api('/system/mqQueues/PRODUCT_PULL/dead-letters')['rows'] if x['event_id']==r['event_id']),None)
  receipt=wait_for(dead,lambda x:x is not None,'Real dead letter was not persisted',100);assert receipt['business_state']=='PENDING' and receipt['death_reason']=='expired';case['dead_id']=receipt['id'];save()
  wait_for(policy,lambda q:any(a['rule_code']=='DEAD_LETTER' for a in q['alerts']),'Quarantine alert missing',60)
  sql("UPDATE oms_message_outbox SET publish_state='PUBLISHED',last_published_at=NOW(3),next_publish_at=DATE_ADD(NOW(3),INTERVAL 180 SECOND),next_process_at=DATE_SUB(NOW(3),INTERVAL 180 SECOND) WHERE company_code='QM' AND event_id="+quote(r['event_id']),CH)
  mode('RUNNING');response=json.loads(docker('exec','oms-local-channel','curl','-fsS','-X','POST','-H','from-source: inner','http://localhost:9304/messages/internal/compensate'));assert response['code']==200;latest=await_task(task,'SUCCESS');assert latest['execution_mode']=='COMPENSATION';receipt=wait_for(dead,lambda x:x['business_state']=='SUCCESS','Quarantine result did not reconcile',100);before=sql("SELECT COUNT(*) FROM oms_message_attempt WHERE event_id="+quote(r['event_id'])+" AND stage='PROCESS' AND result='SUCCESS'",CH);api('/system/mqQueues/dead-letters/'+str(receipt['id'])+'/review',{'reason':'[验收] 已核对原任务由数据库补偿完成，无需重试'});assert api('/system/mqQueues/dead-letters/'+str(receipt['id'])+'/reviews')[0]['reason'].startswith('[验收]');assert before=='1';checked('real broker TTL dead letter retained, compensation result reconciles to success without replay')
  # Browser: separate config entry, dead-letter outcome, review history and current task navigation.
  from playwright.sync_api import sync_playwright,expect
  with sync_playwright() as p:
   b=p.chromium.launch(headless=True);ctx=b.new_context(viewport={'width':1740,'height':1150});ctx.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=ctx.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)));page.goto(BASE+'/monitor/queues',wait_until='networkidle');fixed=page.locator('.el-table__fixed-right');expect(fixed.get_by_role('button',name='运行详情',exact=True).first).to_be_visible();fixed.get_by_role('button',name='配置',exact=True).first.click();drawer=page.locator('.el-drawer:visible');expect(drawer.get_by_text('消费并发 / 实例',exact=True)).to_be_visible();drawer.get_by_role('tab',name='死信核查',exact=True).click();row=drawer.locator('.el-table__body-wrapper tr').filter(has_text='PRODUCTPULL'+str(task));expect(row.get_by_text('业务已完成，无需重试',exact=True)).to_be_visible(timeout=30000);page.screenshot(path=str(OUT/'dead-compensated.png'),full_page=True,animations='disabled');row.get_by_role('button',name='核查历史',exact=True).click();dialog=page.locator('.el-dialog:visible');expect(dialog.get_by_text('[验收] 已核对原任务由数据库补偿完成，无需重试',exact=True)).to_be_visible();dialog.get_by_label('Close').click();row.get_by_role('button',name='核对业务任务',exact=True).click();expect(page.locator('.messages h2')).to_be_visible();expect(page.get_by_placeholder('业务单号 / 消息号 / 追踪号')).to_have_value(r['event_id']);assert not errors,errors;b.close()
  checked('browser configuration entry, quarantine result, review history and scoped task link');save()
 finally:
  if case.get('config_task') and case.get('channel_id'):sql("UPDATE channel_product_pull SET channel_id="+str(case['channel_id'])+" WHERE company_code='QM' AND id="+str(case['config_task'])+" AND channel_id="+str(90000000+case['config_task']),CH)
  edit({k:original[k] for k in ['desired_mode','concurrency','prefetch','retry_limit','retry_base_sec','retry_max_sec','compensate_after_sec','compensate_batch','app_rate','shop_rate','alarm_backlog','alarm_due_sec','alarm_failure_pct']});wait_for(policy,lambda r:r['effective_state']==original['desired_mode'],'Original policy not restored',120)
 print('PASS exception closure retained at outputs/message-exceptions/case.json',flush=True)
if __name__=='__main__':main()
