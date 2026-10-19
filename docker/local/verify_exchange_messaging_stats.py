"""Final deployed counter smoke: temporarily break and restore an owned simulated DETAIL response."""
import json,time
from pathlib import Path
from verify_goods_workspace_ui import login,request
from test_multi_virtual_shipping import sql,quote,wait_for
OUT=Path(__file__).resolve().parents[2]/'outputs/exchange-messaging';DB='qm_oms_saas_order';CH='qm_oms_saas_channel'
def main():
 case=json.loads((OUT/'case.json').read_text('utf-8'));token=login();ch=int(case['channel_id']);first,second=case['dispute_ids'][:2];where=" WHERE UPPER(company_code)='QM' AND channel_id="+str(ch)+' AND dispute_id='+quote(second)
 original=sql('SELECT payload FROM channel_exchange_sim'+where,CH);assert json.loads(original)['dispute_id']==second
 def api(path,body=None):
  r=request(path,token=token,payload=body,method='POST' if body is not None else 'GET');return r.get('data',r)
 def task(id):return api('/order/exchanges/tasks/'+str(id))
 def ready():
  try:return bool(task(case['page_task']))
  except (AssertionError,OSError):return False
 wait_for(ready,lambda r:r,'Order gateway route not ready',150)
 wait_for(lambda:sql("SELECT COUNT(*) FROM oms_exchange_sync WHERE company_code=0x514d AND channel_id="+str(ch)+" AND state IN ('PENDING','RUNNING','RETRY')",DB),lambda n:n=='0','Existing owned source task still active',120)
 def messages(id):return api('/order/messages?type=EXCHANGE_PULL&keyword=EXCHSYNC'+str(id))['rows']
 try:
  damaged=json.loads(original);damaged['dispute_id']='1';sql('UPDATE channel_exchange_sim SET payload='+quote(json.dumps(damaged,ensure_ascii=False))+where,CH)
  t=api('/order/exchanges/sync',{'channel_id':ch,'dispute_ids':first+','+second,'request_key':'EXCHANGE_STATS_'+case['stamp']+'_'+str(int(time.time()))})['id'];wait_for(lambda:task(t),lambda r:int(r['unchanged_count'] or 0)==1,'First unchanged detail did not save',120);event=messages(t)[0];sql("UPDATE oms_message_outbox SET business_attempts=7,next_process_at=NOW(),next_publish_at=NOW(),publish_state='PENDING' WHERE company_code=0x514d AND event_id="+quote(event['event_id'])+" AND process_state IN ('PENDING','WAITING')",DB);failed=wait_for(lambda:task(t),lambda r:r['state']=='FAILED','Owned bad detail did not fail',120);assert int(failed['synced_count'])==2 and int(failed['unchanged_count'])==1 and int(failed['failed_count'])==1
  sql('UPDATE channel_exchange_sim SET payload='+quote(original)+where,CH);api('/order/messages/'+event['event_id']+'/retry',{'reason':'[验收] 已恢复本测试店铺的模拟平台详情，核对增量统计'});after=wait_for(lambda:task(t),lambda r:r['state']=='COMPLETED','Counter smoke retry did not finish',120);assert int(after['synced_count'])==2 and int(after['unchanged_count'])==2 and int(after['failed_count'])==0 and int(after['new_count'])==0 and int(after['updated_count'])==0;chain=api('/order/messages/'+event['event_id'])['chain'];assert chain[0]['parent_event_id']==event['event_id'] and chain[0]['process_state']=='SUCCESS'
  case['final_counter_task']=t;case['final_counter_failed_event']=event['event_id'];case['final_counter_retry_event']=chain[0]['event_id'];(OUT/'case.json').write_text(json.dumps(case,ensure_ascii=False,indent=2),encoding='utf-8');print('PASS deployed incremental counters: partial success, failure and repaired retry count each detail once; task '+str(t),flush=True)
 finally:sql('UPDATE channel_exchange_sim SET payload='+quote(original)+where,CH)
if __name__=='__main__':main()
