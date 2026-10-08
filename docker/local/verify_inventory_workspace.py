"""Read-only API checks and latency sampling for the inventory workspace."""
import subprocess
import time
import statistics
from verify_business import request
from local_db import execute, quote

def login():
    captcha=request('/code')
    answer=subprocess.check_output(['docker','exec','oms-local-redis','redis-cli','--raw','GET','captcha_codes:'+captcha['uuid']],text=True).strip().strip('"')
    return request('/auth/login',{'username':'admin','password':'admin123','uuid':captcha['uuid'],'code':answer})['data']['access_token']

def main():
    token=login()
    try:
        result=request('/inventory/wmsInventory/list?pageSize=20',token=token)
        assert result['code']==200 and result['rows'],result
        row=next(r for r in result['rows'] if r['storeCode']=='VC0001' and r['skuSn']=='test01')
        expected=int(execute("SELECT COUNT(*) FROM wms_inventory_batch WHERE company_code='QM' AND store_code="+quote(row['storeCode'])+" AND sku_sn="+quote(row['skuSn']),'qm_oms_saas_inventory').strip())
        assert row['batchCount']==expected,row
        details=request('/inventory/wmsInventory/'+str(row['id']),token=token)['data']
        assert details['zpActualNumber']==row['zpActualNumber']
        anomalies=request('/inventory/wmsInventory/list?abnormal=true',token=token)
        assert all(not item['consistent'] for item in anomalies['rows'])
        assert request('/inventory/wmsInventory/'+str(row['id'])+'/history',token=token)['code']==200
        batches=request('/inventory/wmsInventory/wmsInventoryBatch/list?storeCode=VC0001&skuSn=test01&pageSize=1',token=token)
        assert batches['total']==expected and len(batches['rows'])==min(1,expected),batches
        assert request('/goods/info/inventoryLookup?skus=test01',token=token)['code']==200
        stores=request('/supplychain/simulationStore/inventoryLookup?codes=VC0001',token=token)
        assert stores['code']==200,stores
        print('PASS scoped inventory list, discrepancy totals, history, batch paging, product/warehouse lookup')
        times=[]
        for _ in range(10):
            start=time.perf_counter();assert request('/inventory/wmsInventory/list?pageSize=20',token=token)['code']==200
            times.append((time.perf_counter()-start)*1000)
        print('Local API latency (small business dataset), ms: median=%.1f max=%.1f'%(statistics.median(times),max(times)))
    finally:request('/auth/logout',{},token,method='DELETE')

if __name__=='__main__':main()
