"""One isolated TEST case: immediate posting and projection while compensation stays paused."""
import json,time
from pathlib import Path
from datetime import datetime
from verify_goods_workspace_ui import login,request,BASE
import test_multi_virtual_shipping as multi
from test_platform_shipping_order import save
from simulate_qimen_delivery import send,mark_simulated_delivery
OUT=Path(__file__).resolve().parents[2]/'outputs/immediate-fulfillment'
MANIFEST=OUT/'case.json'

def main():
    OUT.mkdir(parents=True,exist_ok=True);before=json.loads((OUT/'before.json').read_text(encoding='utf8'));s=login()
    try:
        jobs=request('/schedule/job/list?pageNum=1&pageSize=100',token=s)['rows'];comp=[j for j in jobs if j['invokeTarget'] in ['fulfillmentScanTask.receipts()','fulfillmentScanTask.salesDocuments()']];assert len(comp)==2 and all(j['status']=='1' for j in comp),'Both compensation jobs must already be paused for this acceptance; no operator switch is changed'
        if MANIFEST.exists():case=json.loads(MANIFEST.read_text(encoding='utf8'))
        else:
            stamp=datetime.now().strftime('%Y%m%d%H%M%S');tid='994'+stamp
            case={'case_id':stamp,'created':datetime.now().strftime('%Y-%m-%d %H:%M:%S'),'sku':'ACCEPT_IMM_'+stamp,'channel_name':'[即时验收] 回传与台账同步 '+stamp,'seller':'ACCEPT_IMMEDIATE_'+stamp,'platform_tid':tid,'platform_oid':tid+'01','warehouses':[{'code':'IMMEDIATE_V1_'+stamp,'quantity':6,'package_count':2},{'code':'IMMEDIATE_V2_'+stamp,'quantity':4,'package_count':1}]};save(MANIFEST,case)
        multi.OUTPUT,multi.MANIFEST=OUT,MANIFEST;original=multi.api
        def scoped_api(path,credential,payload=None,post=False):
            response=original(path,credential,payload,post)
            if path=='/order/orders/shipments/dispatch':
                pending=multi.sql("SELECT COUNT(*) FROM oms_order_shipment_dispatch d JOIN oms_order_shipment sh ON sh.company_code=d.company_code AND sh.id=d.shipment_id WHERE d.state IN ('PENDING','PROCESSING','UNKNOWN') AND sh.order_id<>"+str(case['order_id']),'qm_oms_saas_order').strip();assert pending=='0','Another order has pending dispatch work; do not run it for this acceptance'
                job=next(j for j in jobs if j['invokeTarget']=='fulfillmentScanTask.dispatch()');request('/schedule/job/run',token=s,method='PUT',payload={'jobId':job['jobId'],'jobGroup':job['jobGroup']})
            return response
        multi.api=scoped_api
        try:connection=multi.prepare(case,s)
        finally:multi.api=original
        ids=','.join(str(w['shipment_id']) for w in case['warehouses'])
        query="SELECT d.shipment_id,t.sn,(SELECT SUM(g.number_expected) FROM wms_tickets_goods g WHERE g.company_code=t.company_code AND g.sn=t.sn),(SELECT SUM(g.number_actually) FROM wms_tickets_goods g WHERE g.company_code=t.company_code AND g.sn=t.sn),(SELECT COUNT(*) FROM wms_sales_outbound_movement m WHERE m.company_code=d.company_code AND m.ticket_id=d.ticket_id) FROM wms_sales_outbound_document d JOIN wms_tickets t ON t.id=d.ticket_id AND t.company_code=d.company_code WHERE d.company_code='QM' AND d.shipment_id IN ("+ids+") ORDER BY d.shipment_id"
        multi.wait_for(lambda:multi.sql(query,'qm_oms_saas_commodity').splitlines(),lambda rows:len(rows)==2 and sum(int(r.split('\t')[2]) for r in rows)==10,'Immediate initial sales document projection missing',seconds=25)
        secret=next(c for c in json.loads((multi.HERE/'wms-simulator-credentials.json').read_text(encoding='utf8')) if c['app_key']==connection['app_key'] and c['customer_id']==connection['customer_id'])
        phases=[]
        for index,warehouse in enumerate(case['warehouses'],1):
            path='/order/orders/shipments/'+str(warehouse['shipment_id']);head=original(path,s)['data']
            if not head['shipped_quantity']:
                mark_simulated_delivery(warehouse,connection);started=time.monotonic();send(warehouse,connection,secret)
                head=multi.wait_for(lambda:original(path,s)['data'],lambda row:row['shipped_quantity']==warehouse['quantity'],'Callback was not posted immediately with compensation paused',seconds=25);elapsed=round(time.monotonic()-started,3)
            else:elapsed=None
            expected=6 if index==1 else 10;snapshot=multi.stock(case);multi.verify_stock(case,snapshot,expected)
            multi.wait_for(lambda:multi.sql(query,'qm_oms_saas_commodity').splitlines(),lambda rows:len(rows)==2 and sum(int(r.split('\t')[3]) for r in rows)==expected,'Immediate actual sales projection missing',seconds=25)
            actor=multi.sql("SELECT created_by FROM oms_order_shipment_outbound WHERE company_code='QM' AND shipment_id="+str(warehouse['shipment_id']),'qm_oms_saas_order').strip();assert actor=='出库回传即时记账',actor
            assert len(head['outbound_records'])==1 and head['package_count']==warehouse['package_count']
            send(warehouse,connection,secret);assert multi.stock(case)==snapshot,'Duplicate callback changed stock'
            phases.append({'shipment_id':warehouse['shipment_id'],'shipment_sn':warehouse['shipment_sn'],'quantity':warehouse['quantity'],'packages':warehouse['package_count'],'immediate_seconds':elapsed,'actor':actor,'replay_stock_unchanged':True});save(MANIFEST,case);print('PASS immediate phase '+str(index),flush=True)
        final_jobs=request('/schedule/job/list?pageNum=1&pageSize=100',token=s)['rows']
        for old in before['jobs']:
            now=next(j for j in final_jobs if j['jobId']==old['jobId']);assert all(now.get(k)==old.get(k) for k in ['invokeTarget','status','cronExpression','jobGroup'])
        old_case=json.loads((OUT.parent/'receipt-review-pending/case.json').read_text(encoding='utf8'));assert multi.stock(old_case)==before['old_stock']
        review=request('/order/orders/shipments/10/receipt-reviews',token=s)['data']['reviews'];assert [(r['id'],r['state'],r['stock_state'],r.get('event_id')) for r in review]==[(r['id'],r['state'],r['stock_state'],r.get('event_id')) for r in before['old_review']]
        clean=multi.sql("SELECT COUNT(*) FROM oms_order_sales_document_sync WHERE company_code='QM' AND shipment_id IN ("+ids+") AND dirty_version=synced_dirty_version AND revision=synced_revision AND next_attempt IS NULL",'qm_oms_saas_order').strip();assert clean=='2'
        case['completed']=True;case['business_url']=BASE+'/oms-order/business?view=business&orderId='+str(case['order_id']);save(MANIFEST,case)
        result={'order_id':case['order_id'],'order_sn':case['order_sn'],'business_url':case['business_url'],'phases':phases,'compensation_paused_throughout':True,'sales_documents':multi.sql(query,'qm_oms_saas_commodity').splitlines(),'clean_sync_tasks_not_polled':True,'old_acceptance_unchanged':True,'job_switches_and_cron_preserved':True,'real_platform_notification_sent':False};(OUT/'verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8');print('PASS immediate callback and sales sync with both compensation jobs paused',flush=True)
    finally:request('/auth/logout',token=s,method='DELETE')

if __name__=='__main__':main()
