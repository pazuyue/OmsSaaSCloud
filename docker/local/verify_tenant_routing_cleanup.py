"""Read-only regression for the retained cases and scheduler settings after API cleanup."""
import json,sys
from pathlib import Path
from verify_goods_workspace_ui import login,request
import test_multi_virtual_shipping as multi

OUT=Path(__file__).resolve().parents[2]/'outputs/tenant-routing-cleanup'
CASES=['receipt-review-pending','immediate-fulfillment','tenant-routing']

def snapshot():
    token=login()
    try:
        jobs=request('/schedule/job/list?pageNum=1&pageSize=100',token=token)['rows']
        result={'jobs':[{k:j.get(k) for k in ['jobId','invokeTarget','status','cronExpression','jobGroup']} for j in sorted(jobs,key=lambda j:j['jobId'])],'cases':{}}
        for name in CASES:
            case=json.loads((OUT.parent/name/'case.json').read_text(encoding='utf8'))
            order=request('/order/orders/business/'+str(case['order_id']),token=token)['data']
            result['cases'][name]={'stock':multi.stock(case),'order':{k:order.get(k) for k in ['id','order_sn','business_status','shipped_quantity','logical_inventory_status','warehouse_inventory_status']}}
        reviews=request('/order/orders/shipments/10/receipt-reviews',token=token)['data']['reviews']
        result['reviews']=[{k:r.get(k) for k in ['id','state','stock_state','event_id']} for r in reviews]
        return result
    finally:request('/auth/logout',token=token,method='DELETE')

def main():
    assert sys.argv[1:] in (['--baseline'],['--verify'])
    OUT.mkdir(parents=True,exist_ok=True);before=OUT/'before.json'
    if sys.argv[1:]==['--baseline']:
        assert not before.exists(),'Baseline already exists; do not overwrite'
        before.write_text(json.dumps(snapshot(),ensure_ascii=False,indent=2),encoding='utf8');print('Cleanup baseline saved; read-only');return
    current=snapshot();assert current==json.loads(before.read_text(encoding='utf8')),'Retained case or scheduler state changed'
    (OUT/'verification.json').write_text(json.dumps({'three_cases_unchanged':True,'stock_unchanged':True,'review_decisions_unchanged':True,'job_switches_and_cron_unchanged':True},indent=2),encoding='utf8')
    print('PASS cleanup: three retained cases, stock, review decisions and job settings unchanged')

if __name__=='__main__':main()
