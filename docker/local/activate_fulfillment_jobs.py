"""Activate the seven newly installed central jobs after removing embedded timers.
Never changes pre-existing operator jobs or submits a human receipt decision.
"""
import json,time
from pathlib import Path
from urllib.parse import urlencode
from verify_goods_workspace_ui import login,request
from configure_fulfillment_jobs import JOBS
from test_multi_virtual_shipping import stock
OUT=Path(__file__).resolve().parents[2]/'outputs/central-scheduler'

def jobs(token):return request('/schedule/job/list?pageNum=1&pageSize=100',token=token)['rows']
def logs(token,target):return request('/schedule/job/log/list?'+urlencode({'pageNum':1,'pageSize':10,'invokeTarget':target}),token=token)['rows']
def change(token,job,status):request('/schedule/job/changeStatus',token=token,method='PUT',payload={'jobId':job['jobId'],'status':status})
def run(token,job):request('/schedule/job/run',token=token,method='PUT',payload={'jobId':job['jobId'],'jobGroup':job['jobGroup']})
def wait_log(token,target,after=0):
    for _ in range(45):
        rows=[r for r in logs(token,target) if r['jobLogId']>after and r.get('executionData')]
        if rows:return rows[0]
        time.sleep(1)
    raise AssertionError('No structured Quartz execution log: '+target)

def main():
    before=json.loads((OUT/'before.json').read_text(encoding='utf8'))
    case=json.loads((OUT.parent/'receipt-review-pending/case.json').read_text(encoding='utf8'))
    token=login()
    try:
        current=jobs(token);owned=[]
        for title,method,cron,remark in JOBS:
            target='fulfillmentScanTask.'+method+'()';found=[j for j in current if j['invokeTarget']==target];assert len(found)==1,(target,len(found));owned.append(found[0]);assert found[0]['concurrent']=='1'
        for old in before['jobs']:
            now=next(j for j in current if j['jobId']==old['jobId']);assert all(now.get(k)==old.get(k) for k in ['status','cronExpression','invokeTarget','jobGroup']),old['jobId']
        first=[]
        for job in owned:
            previous=max([r['jobLogId'] for r in logs(token,job['invokeTarget'])] or [0]);run(token,job);row=wait_log(token,job['invokeTarget'],previous);data=json.loads(row['executionData']);assert data['scope_failures']==0,(job['jobName'],data);first.append({'job_id':job['jobId'],'job_name':job['jobName'],'invoke_target':job['invokeTarget'],'log_id':row['jobLogId'],'data':data});print('PASS manual Quartz scan:',job['jobName'],flush=True)
        receipt=next(j for j in owned if j['invokeTarget']=='fulfillmentScanTask.receipts()')
        assert next(item['data'] for item in first if item['job_id']==receipt['jobId'])['review_pending']>=0
        for job in owned:change(token,job,'0')
        # Drain any existing run, then verify the paused job stops automatic scans.
        change(token,receipt,'1');time.sleep(6)
        paused=max([r['jobLogId'] for r in logs(token,receipt['invokeTarget'])] or [0]);time.sleep(7)
        assert max([r['jobLogId'] for r in logs(token,receipt['invokeTarget'])] or [0])==paused,'Receipt scan still runs while paused'
        run(token,receipt);manual=wait_log(token,receipt['invokeTarget'],paused);assert json.loads(manual['executionData'])['review_pending']>=0
        change(token,receipt,'0');automatic=wait_log(token,receipt['invokeTarget'],manual['jobLogId'])
        for job in owned:
            initial=next(item['log_id'] for item in first if item['job_id']==job['jobId']);wait_log(token,job['invokeTarget'],initial)
        current=jobs(token);assert all(next(j for j in current if j['jobId']==job['jobId'])['status']=='0' for job in owned)
        for old in before['jobs']:
            now=next(j for j in current if j['jobId']==old['jobId']);assert all(now.get(k)==old.get(k) for k in ['status','cronExpression','invokeTarget','jobGroup'])
        review=request('/order/orders/shipments/'+str(before['pending_shipment_id'])+'/receipt-reviews',token=token)['data']['reviews'][0]
        original=before['review']['reviews'][0]
        assert all(review.get(key)==original.get(key) for key in ['id','state','stock_state','event_id','reason','operator_name','control_version'])
        assert stock(case)==before['stock'],'Retained acceptance inventory changed'
        result={'jobs':first,'all_seven_enabled':True,'all_seven_automatic_scans_observed':True,'old_job_switches_and_cron_preserved':True,'pause_stops_automatic_scans':True,'run_once_works_while_paused':True,'resume_restarts_automatic_scans':True,'acceptance_review_unchanged':True,'acceptance_review_state':review['state'],'acceptance_stock_state':review['stock_state'],'acceptance_stock_unchanged':True,'human_decision_submitted':False}
        (OUT/'verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8');print('PASS central scheduler controls and baseline acceptance inventory unchanged',flush=True)
    finally:request('/auth/logout',token=token,method='DELETE')

if __name__=='__main__':main()
