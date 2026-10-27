"""Read-only API filter, ledger and bounded-query evidence for the retained fixtures."""
import json
import statistics
import time
from datetime import datetime
from pathlib import Path
from urllib.parse import urlencode
from verify_goods_workspace_ui import login,request
from verify_return_management import protected
from test_multi_virtual_shipping import sql,quote

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/supplychain-iteration';API='/supplychain/purchaseWorkspace'


def main():
    manifest=json.loads((OUT/'manifest.json').read_text('utf-8'));cases={c['key']:c for c in manifest['cases']};token=login();checks=[];timings=[]
    def read(path,params=None):return request(API+path+('?' +urlencode(params) if params else ''),token=token)
    for kind,key,state,result,total in [('purchase','full','ACCEPTED','SUCCESS',1),('receipt','dispatch_fail','FAILED','FAILED',1),('receipt','full','FAILED','SUCCESS',0),('ticket','full','ACCEPTED','SUCCESS',1)]:
        q=dict(warehouseState=state,warehouseResult=result);q[{'purchase':'poSn','receipt':'noSn','ticket':'sn'}[kind]]=cases[key][{'purchase':'purchase_sn','receipt':'receipt_sn','ticket':'ticket_sn'}[kind]]
        assert read('/'+kind+'/list',q)['total']==total;(checks.append('组合筛选 '+kind+' / '+key+' / '+state+' / '+result+' = '+str(total)))
    for key,issue,total in [('short','DIFFERENCE',1),('rearrange','DIFFERENCE',0),('bad_keep','DIFFERENCE',0),('corrected','DIFFERENCE',0),('dispatch_fail','DISPATCH',1),('late','REVIEW',1),('overdue','OVERDUE',1)]:
        assert read('/operations',dict(number=cases[key]['purchase_sn'],issue=issue))['data']['total']==total;checks.append('当前待办 '+key+' / '+issue+' = '+str(total))
    assert read('/operations',dict(number=cases['over']['ticket_sn'],issue='DIFFERENCE'))['data']['total']==1;checks.append('完整实际CG单号可直接定位对应到货的差异')
    for q in [dict(pageSize=51),dict(issue='INVALID'),dict(from_='2026-01-01')]:
        if 'from_' in q:q={'from':'2026-01-01','to':'2026-10-25'}
        try:read('/operations',q)
        except AssertionError:pass
        else:raise AssertionError('Invalid unbounded workbench filter was accepted')
    checks.append('拒绝超量分页、无效待办类型及超过93天的显式日期范围')
    endpoints=[('待办默认近90天','/operations',None),('待办完整采购单号','/operations',dict(number=cases['over']['purchase_sn'])),('采购详情','/purchase/'+str(cases['full']['purchase_id']),None),('采购列表仓库状态筛选','/purchase/list',dict(warehouseState='ACCEPTED',pageSize=20))]
    for title,path,q in endpoints:
        read(path,q);samples=[]
        for _ in range(5):
            start=time.perf_counter();read(path,q);samples.append(round((time.perf_counter()-start)*1000,1))
        timings.append(dict(name=title,samples_ms=samples,median_ms=round(statistics.median(samples),1),max_ms=max(samples)))
    db='qm_oms_saas_commodity';plans={}
    plans['采购定位']=sql("EXPLAIN SELECT p.id FROM po_info p WHERE p.company_key='QM' AND p.po_sn="+quote(cases['full']['purchase_sn']),db)
    plans['当前差异索引']=sql("EXPLAIN SELECT id,ticket_id FROM purchase_receipt_difference WHERE company_code='QM' AND state='OPEN' ORDER BY id LIMIT 20",db)
    plans['实际SKU批量读取']=sql("EXPLAIN SELECT * FROM wms_tickets_goods WHERE company_code='QM' AND sn IN("+quote(cases['full']['ticket_sn'])+','+quote(cases['short']['ticket_sn'])+") ORDER BY id",db)
    assert protected()==manifest['protected'],'Protected records changed'
    (OUT/'query-check.json').write_text(json.dumps(dict(checks=checks,timings=timings,explain=plans,protected_baseline_unchanged=True,verified_at=datetime.now().isoformat(),scope='本机保留数据的暖请求；不是生产容量压测'),ensure_ascii=False,indent=2),'utf-8')
    print('PASS',len(checks),'read-only API query checks; protected baseline unchanged')
    for x in timings:print(x['name'],'median_ms',x['median_ms'],'max_ms',x['max_ms'])


if __name__=='__main__':main()
