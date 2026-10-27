"""A dedicated TEST reader may query/export evidence but cannot start, stop, retry or adjust tasks."""
import json,secrets,time,urllib.error,urllib.request,urllib.parse,statistics
from pathlib import Path
from verify_goods_workspace_ui import login,request,BASE
from verify_shared_carrier_permissions import login_as
from test_multi_virtual_shipping import sql,quote

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/full-stock-check';API='/supplychain/warehouseControl'


def main():
    admin=login();stamp=str(int(time.time()));name='FCR_'+stamp;password=secrets.token_urlsafe(12);state=json.loads((OUT/'manifest.json').read_text('utf-8'));id=state['ids']['dual'];menu=[int(v) for v in sql("SELECT menu_id FROM sys_menu WHERE perms='warehouse:reconciliation:query'",'ry-cloud').splitlines()];assert menu
    request('/system/role',dict(roleName='[全仓TEST]只读 '+stamp,roleKey=name,roleSort=80,status='0',dataScope='1',menuCheckStrictly=True,deptCheckStrictly=True,menuIds=menu),token=admin);role=int(sql('SELECT role_id FROM sys_role WHERE role_key='+quote(name),'ry-cloud'));request('/system/user',dict(userName=name,nickName='[全仓TEST]只读',password=password,roleIds=[role],postIds=[],status='0',sex='0',loginCompanyCode='qm',deptId=103),token=admin);token=login_as(name,password)
    for path in [API+'/reconciliations',API+'/reconciliations/'+str(id),API+'/reconciliations/'+str(id)+'/results']:assert request(path,token=token)['data'] is not None
    url=BASE+'/prod-api'+API+'/reconciliations/'+str(id)+'/export?status=PHYSICAL_DIFFERENCE';csv=urllib.request.urlopen(urllib.request.Request(url,headers={'Authorization':'Bearer '+token}),timeout=30).read().decode('utf-8');assert 'WMS' in csv
    invalid_url=BASE+'/prod-api'+API+'/reconciliations/'+str(id)+'/export?status=INVALID!';response=urllib.request.urlopen(urllib.request.Request(invalid_url,headers={'Authorization':'Bearer '+token}),timeout=30);assert 'application/json' in response.headers['Content-Type'];error=json.loads(response.read());assert error['code']==500 and '筛选无效' in error['msg']
    empty_url=BASE+'/prod-api'+API+'/reconciliations/'+str(id)+'/export?status=QUERY_FAILED';empty=urllib.request.urlopen(urllib.request.Request(empty_url,headers={'Authorization':'Bearer '+token}),timeout=30).read().decode('utf-8');assert empty.startswith('SKU,') and len(empty.strip().splitlines())==1
    denied=[];before=sql("SELECT COUNT(*) FROM warehouse_reconciliation WHERE company_code='QM'",'qm_oms_saas_commodity')
    for path,payload in [(API+'/reconciliations',dict(scope_mode='FULL',store_code=state['store'],request_key='DENIED_'+stamp)),(API+'/reconciliations/'+str(id)+'/control/stop',{}),(API+'/reconciliations/'+str(id)+'/control/retryFailed',{}),(API+'/reconciliations/'+str(id)+'/recheck',dict(request_key='DENIED_'+stamp,skus=['A'])),(API+'/reconciliations/'+str(id)+'/adjustment',{})]:
        try:request(path,payload=payload,token=token)
        except AssertionError as e:assert any(t in str(e) for t in ['权限','403','无权']),str(e);denied.append(path)
        except urllib.error.HTTPError as e:assert e.code in [400,403];denied.append(path)
        else:raise AssertionError('Reader mutation was not blocked: '+path)
    assert before==sql("SELECT COUNT(*) FROM warehouse_reconciliation WHERE company_code='QM'",'qm_oms_saas_commodity')
    timings=[];logs=sql('SELECT COUNT(*) FROM wms_interaction_log','qm_oms_saas_commodity')
    for path in [API+'/reconciliations',API+'/reconciliations/'+str(id),API+'/reconciliations/'+str(id)+'/results?status=ABNORMAL&pageSize=20']:
        samples=[]
        for _ in range(5):
            start=time.perf_counter();request(path,token=token);samples.append(round((time.perf_counter()-start)*1000,1))
        timings.append(dict(path=path,samples_ms=samples,median_ms=statistics.median(samples)))
    assert logs==sql('SELECT COUNT(*) FROM wms_interaction_log','qm_oms_saas_commodity')
    (OUT/'permissions-performance.json').write_text(json.dumps(dict(result='PASS',reader=name,query_and_export_allowed=True,export_errors_are_json=True,empty_export_has_headers=True,mutations_denied=denied,no_task_created=True,no_warehouse_calls_for_reads=True,timings=timings,scope='本地19种商品任务，单用户抽样，包含网关认证；不作为大仓压测结论',limits=dict(discovery_inventory_rows=100,sku_queries_per_round=5,same_warehouse_query_concurrency=1,result_page=100,wms_page=100,wms_rows_per_sku=5000,manifest_skus=20000,export_rows=100000)),ensure_ascii=False,indent=2),'utf-8');print('PASS reader query/export, five denied mutations and bounded read latency checks')


if __name__=='__main__':main()
