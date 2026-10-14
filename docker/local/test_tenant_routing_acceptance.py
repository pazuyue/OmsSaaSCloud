"""Verify unified routing with an isolated TEST order; keep existing job settings and stock."""
import json,subprocess,sys
from pathlib import Path
from verify_goods_workspace_ui import login,request
import test_multi_virtual_shipping as multi
import test_immediate_fulfillment as immediate
OUT=Path(__file__).resolve().parents[2]/'outputs/tenant-routing'

def baseline():
    OUT.mkdir(parents=True,exist_ok=True);token=login()
    try:
        if (OUT/'before.json').exists():return
        old=json.loads((OUT.parent/'receipt-review-pending/case.json').read_text(encoding='utf8'))
        jobs=request('/schedule/job/list?pageNum=1&pageSize=100',token=token)['rows']
        before={'jobs':[{k:j.get(k) for k in ['jobId','invokeTarget','status','cronExpression','jobGroup']} for j in jobs],'old_stock':multi.stock(old),'old_review':request('/order/orders/shipments/10/receipt-reviews',token=token)['data']['reviews']}
        (OUT/'before.json').write_text(json.dumps(before,ensure_ascii=False,indent=2,default=str),encoding='utf8')
        previous=json.loads((OUT.parent/'immediate-fulfillment/case.json').read_text(encoding='utf8'))
        (OUT/'previous-stock.json').write_text(json.dumps(multi.stock(previous),ensure_ascii=False,indent=2),encoding='utf8')
        print('Baseline saved; no task switches changed')
    finally:request('/auth/logout',token=token,method='DELETE')

def run():
    immediate.OUT=OUT;immediate.MANIFEST=OUT/'case.json';original=multi.api
    def isolated_api(path,token,payload=None,post=False):
        if path.startswith('/inventory/api/WmsInventory/addInventory?company_code=QM'):
            result=subprocess.run(['wsl.exe','--','python3','/mnt/d/Users/yueguang/OmsSaaSCloud/docker/local/invoke_tenant_test_inventory.py'],input=json.dumps(payload,ensure_ascii=False).encode(),capture_output=True)
            if result.returncode:raise RuntimeError('Isolated TEST inventory helper failed: '+result.stderr.decode('utf8',errors='replace')[-1000:])
            return json.loads(result.stdout)
        return original(path,token,payload,post)
    multi.api=isolated_api
    try:immediate.main()
    finally:multi.api=original
    previous=json.loads((OUT.parent/'immediate-fulfillment/case.json').read_text(encoding='utf8'))
    assert multi.stock(previous)==json.loads((OUT/'previous-stock.json').read_text(encoding='utf8'))
    import verify_immediate_fulfillment_ui as ui
    ui.OUT=OUT;ui.main()
    print('PASS unified routing acceptance; existing acceptance stock and job settings preserved')

if __name__=='__main__':
    assert sys.argv[1:] in (['--baseline'],['--run'])
    baseline() if sys.argv[1:] == ['--baseline'] else run()
