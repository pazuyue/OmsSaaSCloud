"""Read-only API projections, signed-protocol evidence, SQL plans and protected baselines."""
import json,statistics,time
from pathlib import Path
from datetime import datetime
from urllib.parse import urlencode
from verify_goods_workspace_ui import login,request
from verify_return_management import protected
from test_multi_virtual_shipping import sql,quote

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/purchase-returns';RET='/supplychain/purchaseReturns'


def main():
    m=json.loads((OUT/'manifest.json').read_text('utf-8'));cases={c['key']:c for c in m['cases']};token=login();checks=[];timings=[]
    def read(path,q=None):return request(path+('?' +urlencode(q) if q else ''),token=token)['data']
    full=cases['full'];r=read(RET,dict(return_sn=full['return_sn'],dispatch_state='ACCEPTED',state='COMPLETED'));assert r['total']==1 and r['rows'][0]['latestInteraction']['result']=='SUCCESS';checks.append('仓库受理、单据完成和最近交互成功可组合查询，元数据不替代实出')
    assert read(RET,dict(return_sn=full['return_sn'],dispatch_state='FAILED'))['total']==0;checks.append('已受理的完成单不误归为仓库下发失败')
    failure=cases['dispatch_fail'];r=read(RET,dict(return_sn=failure['return_sn'],dispatch_state='FAILED'));assert r['total']==1 and r['rows'][0]['latestInteraction']['result']=='FAILED';checks.append('明确拒绝下发保留失败记录和原占用，可通过仓库状态快速查询')
    r=read(RET+'/sources',dict(purchaseId=full['purchase_id']));s=r['rows'][0];assert int(s['source_quantity'])==10 and int(s['claimed_quantity'])==3 and int(s['remaining_quantity'])==7 and int(s['selectable_quantity'])==7;checks.append('原成功入账10件、已退供占用3件、当前可选7件同时核对')
    r=read(RET,dict(purchase_id=full['purchase_id']));assert sum(int(x['quantity']) for x in r['returned_by_sku'])==3;checks.append('原采购已退仓投影按实际扣账3件统计，保留原累计实收10件')
    for key,issue in [('over','REVIEW'),('wrong_batch','REVIEW'),('dispatch_fail','DISPATCH')]:
        r=read('/supplychain/purchaseWorkspace/operations',dict(number=cases[key]['return_sn'],issue=issue));assert any(x['kind']=='purchaseReturn' and int(x['document_id'])==cases[key]['return_id'] for x in r['rows']);checks.append('统一待办定位 '+key+'，保持原退仓单处理入口')
    mixed=cases['mixed'];history=sql("SELECT COUNT(*),COUNT(DISTINCT request_id),SUM(-change_quantity) FROM wms_inventory_change_history WHERE company_code='QM' AND sku_sn="+quote(mixed['sku'])+" AND operation_type='RETURN_OUT'",'qm_oms_saas_inventory').split('\t');assert list(map(int,history))==[2,2,3];checks.append('混合正次品生成两条不同库存幂等流水，共扣3件，无重复扣账')
    db='qm_oms_saas_commodity'
    for key in ['full','bad','mixed','partial_cancel']:
        sn=cases[key]['return_sn'];rows=sql("SELECT action,request_body FROM wms_interaction_log WHERE company_code='QM' AND ticket_sn="+quote(sn)+" AND direction='OUT' ORDER BY id",db)
        assert 'PURCHASE_RETURN_CREATE' in rows and '<orderType>CGTH</orderType>' in rows
        if key=='bad':assert '<inventoryType>CC</inventoryType>' in rows
        if key=='partial_cancel':assert 'PURCHASE_RETURN_CANCEL' in rows and '<orderCode>'+sn+'</orderCode>' in rows
    checks.append('实际发送报文核对CGTH、次品CC及原单取消字段，未套用销售发货单')
    funds=read(RET+'/'+str(cases['funds']['return_id']));assert funds['funds_state']=='CREDIT' and float(funds['funds_amount'])==float(funds['posted_amount'])==7.5;checks.append('供应商凭证独立登记7.50元，与已出库原采购货值一致')
    for key in ['freeze','unfreeze','convert','bad_freeze','mixed','accepted']:
        row=request('/inventory/productInventory?'+urlencode(dict(skuSn=cases[key]['sku'])),token=token)['rows'][0]
        assert row['checkStatus']=='CONSISTENT',(key,row['checkStatus'])
        expected=cases[key]['final_stock']['company']
        assert all(int(row[field])==expected[name] for field,name in [('totalStock','total'),('availableStock','available'),('allocatedStock','allocated'),('reservedStock','reserved'),('frozenStock','frozen')]),(key,row)
    checks.append('商品库存对账包含不可售与质量冻结，冻结解冻转正及混合退供均与仓库和批次一致')
    for endpoint in [RET,'/inventory/qualityInventory']:
        try:read(endpoint,dict(company_code='OTHER'))
        except Exception:pass
        else:raise AssertionError('Public cross-company selection unexpectedly accepted')
    checks.append('公开接口不能通过company_code选择其他公司')
    endpoints=[('退仓列表',RET,dict(pageSize=20)),('退仓完整单号',RET,dict(return_sn=full['return_sn'])),('原入库可退来源',RET+'/sources',dict(purchaseId=full['purchase_id'])),('质量库存列表','/inventory/qualityInventory',dict(sku_sn=cases['convert']['sku'])),('供应链退仓待办','/supplychain/purchaseWorkspace/operations',dict(number=cases['over']['return_sn']))]
    for title,path,q in endpoints:
        read(path,q);samples=[]
        for _ in range(5):
            start=time.perf_counter();read(path,q);samples.append(round((time.perf_counter()-start)*1000,1))
        timings.append(dict(name=title,samples_ms=samples,median_ms=round(statistics.median(samples),1),max_ms=max(samples)))
    plans=dict(return_number=sql("EXPLAIN SELECT id FROM purchase_return WHERE company_code='QM' AND return_sn="+quote(full['return_sn']),db),quality_sku=sql("EXPLAIN SELECT * FROM oms_stock_care_line WHERE company_code='QM' AND sku_sn="+quote(cases['freeze']['sku']),'qm_oms_saas_inventory'))
    assert protected()==m['protected'],'Protected old order/refund/inventory/job baseline changed'
    (OUT/'query-check.json').write_text(json.dumps(dict(checks=checks,timings=timings,explain=plans,protected_baseline_unchanged=True,verified_at=datetime.now().isoformat()),ensure_ascii=False,indent=2),'utf-8');print('PASS',len(checks),'query/protocol checks; protected original records unchanged')
    for t in timings:print(t['name'],'median_ms',t['median_ms'],'max_ms',t['max_ms'])


if __name__=='__main__':main()
