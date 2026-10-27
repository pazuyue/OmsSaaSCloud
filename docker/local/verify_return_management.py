"""Read-only business acceptance of the return management workspace; preserve all source documents."""
import hashlib
import json
import sys
import time
from datetime import datetime
from pathlib import Path
from test_multi_virtual_shipping import sql,quote
from verify_goods_workspace_ui import login,request

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'outputs/return-management'
ORDER='qm_oms_saas_order';INV='qm_oms_saas_inventory';SUPPLY='qm_oms_saas_commodity'


def protected():
    files={}
    for name in ['exchange-automation','exchange-partial-continuous','exchange-return-split']:
        path=ROOT/'outputs'/name/'manifest.json';files[name]=hashlib.sha256(path.read_bytes()).hexdigest()
    stock=sql("SELECT sku_sn,total_stock,available_stock,reserved_stock,allocated_stock,frozen_stock FROM oms_inventory WHERE company_code='QM' AND (sku_sn LIKE 'EXAUTO%' OR sku_sn LIKE 'RR20261024185730%') ORDER BY sku_sn",INV)
    physical=sql("SELECT store_code,sku_sn,zp_actual_number,zp_available_number,zp_lock_number,cp_actual_number FROM wms_inventory WHERE company_code='QM' AND (sku_sn LIKE 'EXAUTO%' OR sku_sn LIKE 'RR20261024185730%') ORDER BY store_code,sku_sn",INV)
    return {'manifests':files,'stock':stock,'warehouse':physical,'refunds':sql("SELECT id,state,review_state,goods_state,funds_state,refund_fee FROM oms_refund WHERE company_code='QM' AND id IN (118,119,121,122,126,127,131,132,133) ORDER BY id",ORDER),'queues':sql('SELECT business_type,desired_mode,concurrency,prefetch,app_rate,shop_rate,revision FROM sys_mq_queue_policy ORDER BY business_type','ry-cloud'),'job':sql("SELECT status FROM sys_job WHERE invoke_target='exchangeTask.scanAll()'",'ry-cloud')}


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    if sys.argv[1:]==['--capture']:
        path=OUT/'protected-baseline.json';assert not path.exists(),'Keep the original baseline'
        path.write_text(json.dumps(protected(),ensure_ascii=False,indent=2),'utf-8');print('Captured retained documents, stock, queues and global-job baseline');return
    assert not sys.argv[1:]
    token=login()
    def api(path):return request(path,token=token)['data']
    from urllib.parse import urlencode
    def query(**params):return api('/order/returns?'+urlencode(params))
    for attempt in range(60):
        r=query(view='TASK',pageSize=20)
        if r['synchronization'].get('warning'):raise AssertionError(r['synchronization'])
        if not r['synchronization']['updating']:break
        time.sleep(1)
    else:raise AssertionError('Read projection has not caught up; source data unchanged')
    case=json.loads((ROOT/'outputs/exchange-return-split/manifest.json').read_text('utf-8'))['cases']['24'];refund=case['final_refund']['refund_sn'];exchange=case['final_exchanges'][1]['exchange_sn'];doc=case['shared_return']['document']['return_sn'];parcel=case['shared_return']['parcel']['parcel_sn']
    evidence={'verified_at':datetime.now().isoformat(),'case24':{'order':case['order_sn'],'refund':refund,'exchange':exchange,'parcel':parcel,'document':doc},'checks':[]}
    def check(name,passed,actual):
        assert passed,(name,actual)
        evidence['checks'].append({'name':name,'result':'PASS','actual':actual})
    for number in [refund,exchange,case['platform_oid'],case['shared_return']['parcel']['waybill'],doc]:
        r=query(view='PARCEL',number=number);rows=[x for x in r['rows'] if x['parcel_sn']==parcel];check('Any source/waybill/warehouse number finds complete mixed package',len(rows)==1 and rows[0]['source_count']==2 and rows[0]['refund_count']==1 and rows[0]['exchange_count']==1 and rows[0]['units'][0]['quantity']==2,{'number':number,'rows':rows})
    r=query(view='TASK',number=refund);check('Refund task is actual B1',r['total']==1 and r['rows'][0]['members'][0]['sku_sn']==case['target_sku'] and r['rows'][0]['units'][0]['posted_quantity']==1,r)
    r=query(view='TASK',number=exchange);check('Exchange task is actual B1',r['total']==1 and r['rows'][0]['members'][0]['sku_sn']==case['target_sku'] and r['rows'][0]['units'][0]['posted_quantity']==1,r)
    r=query(view='DOCUMENT',number=doc);check('One actual common warehouse command, no additional private RSL rows',r['total']==1 and r['rows'][0]['units'][0]['quantity']==2 and r['rows'][0]['units'][0]['posted_quantity']==2,r)
    r=query(view='PARCEL',number=refund,merge='MIXED');check('Mixed merge filter',r['total']==1,r)
    detail=api('/order/returns/detail?'+urlencode({'view':'PARCEL','key':r['rows'][0]['group_key'],'number':refund,'numberType':'OWNER'}));check('Lookup highlights refund owner and retains exchange member',detail['matched_count']==1 and len(detail['members'])==2 and all(m['posted_quantity']==1 for m in detail['members']),detail)
    partial=json.loads((ROOT/'outputs/exchange-partial-continuous/manifest.json').read_text('utf-8'))['cases']['19'];number=partial['claims'][0]['exchange_sn'];r=query(view='TASK',number=number);check('Partial receipt remains pending, never completed',r['total']==1 and r['rows'][0]['stage']=='PARTIAL' and r['rows'][0]['units'][0]['quantity']==2 and r['rows'][0]['units'][0]['accepted_quantity']==1,r)
    gift=sql("SELECT b.refund_sn FROM oms_refund_return_plan p JOIN oms_refund b ON b.company_code=p.company_code AND b.id=p.refund_id WHERE p.company_code='QM' AND p.gift_plan_id IS NOT NULL ORDER BY p.id DESC LIMIT 1",ORDER)
    if gift:
        r=query(view='TASK',number=gift,itemKind='GIFT');check('Gift return can be independently queried',r['total']>0 and all(x['has_gift'] for x in r['rows']),r)
    for kind,statement,mode in [
        ('exchange',"SELECT p.parcel_sn FROM wms_shared_return_parcel p JOIN wms_shared_return_member m ON m.company_code=p.company_code AND m.parcel_id=p.id WHERE p.company_code='QM' GROUP BY p.id HAVING COUNT(DISTINCT IF(m.owner_kind='EXCHANGE',m.owner_id,NULL))>1 AND COUNT(DISTINCT IF(m.owner_kind='REFUND',m.owner_id,NULL))=0 ORDER BY p.id DESC LIMIT 1",'EXCHANGE_MULTI'),
        ('refund',"SELECT p.parcel_sn FROM wms_refund_parcel p JOIN wms_refund_parcel_member m ON m.company_code=p.company_code AND m.parcel_id=p.id WHERE p.company_code='QM' GROUP BY p.id HAVING COUNT(DISTINCT m.refund_id)>1 ORDER BY p.id DESC LIMIT 1",'REFUND_MULTI')]:
        number=sql(statement,SUPPLY)
        if number:
            r=query(view='PARCEL',number=number,merge=mode);check('Multiple '+kind+' owners share one query package',r['total']==1 and r['rows'][0][kind+'_count']>1 and not r['rows'][0]['incomplete'],r)
    r=query(view='PARCEL',number=refund,numberType='OWNER');wrong=query(view='PARCEL',number=refund,numberType='WAYBILL');check('Typed number lookup respects identity type',r['total']==1 and wrong['total']==0,{'owner':r['total'],'wrong_type':wrong['total']})
    only=sql("SELECT b.refund_sn FROM oms_refund b JOIN oms_platform_refund p ON p.company_code=b.company_code AND p.id=b.platform_refund_id WHERE b.company_code='QM' AND p.dispute_type='REFUND' AND EXISTS(SELECT 1 FROM oms_refund_return_plan rp WHERE rp.company_code=b.company_code AND rp.refund_id=b.id) ORDER BY b.id DESC LIMIT 1",ORDER)
    if only:
        r=query(view='TASK',number=only,refundOnly='1');check('Refund-only application with physical returns remains queryable',r['total']>0 and all(x['refund_only'] for x in r['rows']),r)
    missing=sql("SELECT b.refund_sn FROM oms_refund_goods_task g JOIN oms_refund b ON b.company_code=g.company_code AND b.id=g.refund_id WHERE g.company_code='QM' AND g.action IN ('RETURN','INTERCEPT','REFUSAL') AND g.return_plan_id IS NULL AND g.state<>'CANCELED' ORDER BY g.id DESC LIMIT 1",ORDER)
    if missing:
        r=query(view='TASK',number=missing);check('Return intent without warehouse command remains visible',any(not x['return_sn'] for x in r['rows']),r)
    from urllib.error import HTTPError
    try:
        request('/order/returns?view=TASK&pageSize=999',token=token)
        raise AssertionError('Invalid page size unexpectedly accepted')
    except (AssertionError,HTTPError) as ex:
        if str(ex)=='Invalid page size unexpectedly accepted':raise
        assert '每页最多50条' in str(ex),str(ex)
        evidence['checks'].append({'name':'Invalid page size rejected','result':'PASS','actual':str(ex)})
    try:
        request('/order/returns?view=TASK&company_code=OTHER',token=token)
        raise AssertionError('Public company override unexpectedly accepted')
    except HTTPError as ex:
        assert ex.code==400
        evidence['checks'].append({'name':'Public request cannot select another company','result':'PASS','actual':'HTTP 400'})
    baseline=json.loads((OUT/'protected-baseline.json').read_text('utf-8'));check('All retained inventory/documents/queue/job settings unchanged',protected()==baseline,True)
    evidence['source_data_preserved']=True;(OUT/'acceptance.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),'utf-8');print('PASS',len(evidence['checks']),'return-management acceptance checks; source data and inventory unchanged')


if __name__=='__main__':main()
