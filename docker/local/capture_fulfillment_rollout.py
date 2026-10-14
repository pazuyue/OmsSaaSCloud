"""Capture existing operator switches and the retained pending acceptance state."""
import json
from pathlib import Path
from verify_goods_workspace_ui import login, request
from test_multi_virtual_shipping import stock
OUT=Path(__file__).resolve().parents[2]/'outputs/central-scheduler'
OUT.mkdir(parents=True,exist_ok=True)
if (OUT/'before.json').exists():raise SystemExit('Rollout snapshot already exists; preserve it')
case=json.loads((OUT.parent/'receipt-review-pending/case.json').read_text(encoding='utf8'))
token=login()
try:
    jobs=request('/schedule/job/list?pageNum=1&pageSize=100',token=token)['rows']
    before={'jobs':[{key:job.get(key) for key in ['jobId','jobName','jobGroup','invokeTarget','cronExpression','status']} for job in jobs], 'stock':stock(case),'pending_shipment_id':case['warehouses'][1]['shipment_id'],'review':request('/order/orders/shipments/'+str(case['warehouses'][1]['shipment_id'])+'/receipt-reviews',token=token)['data']}
    (OUT/'before.json').write_text(json.dumps(before,ensure_ascii=False,indent=2,default=str),encoding='utf8')
    print('Saved existing job switches and retained pending-case stock')
finally:request('/auth/logout',token=token,method='DELETE')
