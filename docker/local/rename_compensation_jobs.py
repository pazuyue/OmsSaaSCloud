"""Rename only the two compensation jobs, preserving every switch and cron."""
import json
from pathlib import Path
from configure_fulfillment_jobs import JOBS
from verify_goods_workspace_ui import login,request
import test_multi_virtual_shipping as multi
OUT=Path(__file__).resolve().parents[2]/'outputs/immediate-fulfillment'
OUT.mkdir(parents=True,exist_ok=True)
s=login()
try:
    jobs=request('/schedule/job/list?pageNum=1&pageSize=100',token=s)['rows']
    if not (OUT/'before.json').exists():
        old=json.loads((OUT.parent/'receipt-review-pending/case.json').read_text(encoding='utf8'))
        before={'jobs':[{k:j.get(k) for k in ['jobId','invokeTarget','status','cronExpression','jobGroup']} for j in jobs],'old_stock':multi.stock(old),'old_review':request('/order/orders/shipments/10/receipt-reviews',token=s)['data']['reviews']}
        (OUT/'before.json').write_text(json.dumps(before,ensure_ascii=False,indent=2,default=str),encoding='utf8')
    for title,method,cron,remark in JOBS:
        if method not in ('receipts','salesDocuments'):continue
        job=next(j for j in jobs if j['invokeTarget']=='fulfillmentScanTask.'+method+'()')
        body={key:job.get(key) for key in ['jobId','jobName','jobGroup','invokeTarget','cronExpression','misfirePolicy','concurrent','status','remark']};body.update(jobName=title,remark=remark)
        request('/schedule/job',token=s,method='PUT',payload=body)
    print('Two compensation job names updated; switches and cron preserved')
finally:request('/auth/logout',token=s,method='DELETE')
