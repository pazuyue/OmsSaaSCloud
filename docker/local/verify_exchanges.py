"""Retained, synthetic local exchange acceptance. No real platform/customer writes.

Only the trusted simulation application and TEST Qimen connection are used.
Business APIs perform reservation, allocation, receipt and inventory posting.
"""
import hashlib
import json
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE
from test_multi_virtual_shipping import sql, quote, wait_for
from wms_qimen_simulator import sign
import verify_allocation_rules_v2 as fixture

OUT=Path(__file__).resolve().parents[2]/'outputs/exchanges'
ORDER='qm_oms_saas_order'
CHANNEL='qm_oms_saas_channel'


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    manifest=OUT/'case.json'
    if manifest.exists():
        c=json.loads(manifest.read_text(encoding='utf-8'))
    else:
        stamp=datetime.now().strftime('%Y%m%d%H%M%S');tid='997'+stamp
        c={'case_id':stamp,'created':datetime.now().strftime('%Y-%m-%d %H:%M:%S'),'sku':'EXCH_A_'+stamp,'target_sku':'EXCH_B_'+stamp,'channel_name':'[换货验收] 普通换货 '+stamp,'seller':'EXCH_'+stamp,'platform_tid':tid,'platform_oid':tid+'01','dispute_id':'998'+stamp+'01','warehouses':[{'code':'EXCH_V_'+stamp,'quantity':10,'package_count':1}]}
        save(manifest,c)
    assert c['sku'].startswith('EXCH_A_') and c['platform_tid'].startswith('997')
    token=login()
    def api(path,data=None):return request(path,payload=data,token=token,method='POST' if data is not None else 'GET')
    def business(id):return api('/order/orders/business/'+str(id))['data']
    def exchange():return api('/order/exchanges/'+str(c['exchange_id']))['data']
    def act(action,extra=None):
        current=exchange();return api('/order/exchanges/'+str(current['id'])+'/'+action,{'control_version':current['control_version'],**(extra or {})})['data']
    def receive(sku,qty,key):
        assert sku.startswith('EXCH_')
        payload={'relationSn':key,'wmsInventoryBatch':{'companyCode':'QM','storeCode':c['warehouses'][0]['code'],'skuSn':sku,'batchCode':key,'zpActualNumber':qty,'zpAvailableNumber':qty,'transactionPrice':2.5}}
        result=subprocess.run(['wsl.exe','--','docker','exec','-i','oms-local-inventory','curl','-fsS','-H','from-source: inner','-H','Content-Type: application/json','--data-binary','@-','http://localhost:9303/api/WmsInventory/addInventory?company_code=QM'],input=json.dumps(payload).encode(),capture_output=True,timeout=45)
        assert result.returncode==0 and json.loads(result.stdout)['code']==200,'Local fixture stock receipt failed'
    def shipment(id):return api('/order/orders/shipments/'+str(id))['data']
    def allocate_and_dispatch(order):
        o=business(order)
        if o['allocation_status']!='FULL':
            result=api('/order/orders/business/allocate-batch',{'items':[{'id':o['id'],'control_version':o['control_version']}]})['data'];assert result[0]['success'],result
        o=business(order);assert len(o['shipments'])==1
        sh=shipment(o['shipments'][0]['id'])
        if sh['carrier_match_status']!='MATCHED':
            result=api('/order/orders/shipments/match-carrier',{'items':[{'id':sh['id'],'control_version':sh['control_version']}]})['data'];assert result[0]['success'],result;sh=shipment(sh['id'])
        if sh['wms_dispatch_state']=='NONE':
            result=api('/order/orders/shipments/dispatch',{'items':[{'id':sh['id'],'control_version':sh['control_version']}]})['data'];assert result[0]['success'],result
        sh=shipment(sh['id'])
        if sh['wms_dispatch_state']=='PENDING':api('/order/orders/shipments/'+str(sh['id'])+'/continue-dispatch',{'control_version':sh['control_version']})
        return wait_for(lambda:shipment(sh['id']),lambda s:s['wms_dispatch_state']=='ACCEPTED','Synthetic WMS dispatch did not complete')
    def ship(sh):
        assert sh['warehouse_target']['environment']=='TEST'
        if sh['shipped_quantity']!=sh['quantity']:
            result=subprocess.run([sys.executable,str(Path(__file__).with_name('simulate_qimen_delivery.py')),sh['shipment_sn']],capture_output=True,text=True,encoding='utf-8',timeout=120)
            assert result.returncode==0,'Synthetic shipment completion failed: '+result.stdout[-300:]+result.stderr[-400:]
        return wait_for(lambda:shipment(sh['id']),lambda s:s['shipped_quantity']==s['quantity'],'Synthetic outbound posting incomplete')
    try:
        fixture.MANIFEST=manifest
        original_api=fixture.api
        def fixture_api(path,credential,payload=None,post=False):
            if path=='/inventory/api/WmsInventory/addInventory?company_code=QM':payload['wmsInventoryBatch']['transactionPrice']=2.5
            return original_api(path,credential,payload,post)
        fixture.api=fixture_api
        if not c.get('prepared'):
            fixture.prepare_case(c,token);c['prepared']=True;save(manifest,c)
        original=allocate_and_dispatch(c['order_id']);original=ship(original);c['original_shipment_id']=original['id'];c['original_shipment_sn']=original['shipment_sn'];save(manifest,c)
        if not c.get('target_ready'):
            sample=api('/goods/info/list?pageNum=1&pageSize=1',{})['rows'][0]
            if not sql("SELECT id FROM goods_sku_sn_info WHERE UPPER(company_code)='QM' AND sku_sn="+quote(c['target_sku']),'qm_oms_saas_commodity'):
                api('/goods/info',{'skuSn':c['target_sku'],'goodsSn':c['target_sku'],'goodsName':'[换货验收] 换出商品','categoryCode':sample['categoryCode'],'colorCode':sample['colorCode'],'sizeCode':sample['sizeCode'],'marketPrice':1,'validity':30})
            receive(c['target_sku'],2,'TEST-EXCH-B-'+c['case_id'])
            scope=json.dumps([c['warehouses'][0]['code']]);sql(f"INSERT IGNORE INTO oms_channel_inventory(company_code,channel_id,sku_sn,quota_stock,stock_scope_json) VALUES ('QM',{c['channel_id']},{quote(c['target_sku'])},10,{quote(scope)})",'qm_oms_saas_inventory')
            c['target_ready']=True;save(manifest,c)
        if not c.get('authorized'):
            app=next(a for a in api('/channel/platform/apps')['data'] if int(a.get('simulation_mode',0))==1)
            api(f"/channel/platform/shops/{c['channel_id']}/binding",{'app_id':app['id'],'expected_shop_id':'700'+str(c['channel_id'])})
            api(f"/channel/platform/shops/{c['channel_id']}/simulate",{})
            capability=api('/order/exchanges/capability/'+str(c['channel_id']))['data'];assert capability['simulation'] and capability['can_query']
            api('/channel/exchangePlatform/'+str(c['channel_id'])+'/access',{'revision':capability['revision'],'read_enabled':1,'write_enabled':1})
            api('/order/exchanges/configs',{'channel_id':c['channel_id'],'revision':0,'initial_time':(datetime.now()-timedelta(minutes=5)).strftime('%Y-%m-%d %H:%M:%S'),'enabled':1,'write_enabled':1})
            c['authorized']=True;save(manifest,c)
        if not c.get('exchange_id'):
            payload={'dispute_id':c['dispute_id'],'biz_order_id':c['platform_oid'],'num':2,'status':'1','good_status':'已发货','modified':datetime.now().strftime('%Y-%m-%d %H:%M:%S'),'time_out':(datetime.now()+timedelta(days=2)).strftime('%Y-%m-%d %H:%M:%S'),'bought_sku':'原商品规格','exchange_sku':'换出规格描述，需匹配 OMS SKU','speed_exchange':0,'refund_phase':'yes'}
            sql(f"INSERT IGNORE INTO channel_exchange_sim(company_code,channel_id,dispute_id,payload,modified) VALUES ('qm',{c['channel_id']},{quote(c['dispute_id'])},{quote(json.dumps(payload,ensure_ascii=False))},{quote(payload['modified'])})",CHANNEL)
            rows=wait_for(lambda:api('/order/exchanges?number='+c['dispute_id'])['rows'],lambda r:len(r)==1,'Automatic exchange synchronization did not create the handling document',120)
            c['exchange_id']=rows[0]['id'];assert rows[0]['state']=='NEEDS_REVIEW' and 'SKU_UNMATCHED' in rows[0]['problem_codes'];save(manifest,c)
        current=exchange()
        if not current.get('approved_version'):
            assert sql("SELECT reserved_stock FROM oms_inventory WHERE company_code='QM' AND sku_sn="+quote(c['target_sku']),'qm_oms_saas_inventory')=='0'
            act('review',{'target_sku':c['target_sku'],'return_store':c['warehouses'][0]['code'],'platform_address_id':'991','original_shipment_id':c['original_shipment_id'],'reason':'本地模拟验收：普通换货，原单已全部发货，换2件','recipient_confirmed':True,'replacement_recipient':current['replacement_recipient']})
        current=exchange()
        if current['platform_status']=='1' and current['platform_operation']=='NONE':act('execute',{'operation':'AGREE','reason':'本地模拟：检查库存并同意换货'})
        current=wait_for(exchange,lambda e:e['platform_status'] in ('2','3','12','13'),'Platform agreement was not confirmed',90)
        if not current['return_sn']:current=act('advance')
        current=wait_for(exchange,lambda e:bool(e['return_sn']),'Return document not prepared')
        c['return_sn']=current['return_sn'];save(manifest,c)
        connection=next(x for x in api('/supplychain/wmsIntegration/connections')['data'] if x['id']==c['connection_id'])
        assert connection['environment']=='TEST' and connection['endpoint']=='http://wms-simulator:8080/qimen/gateway'
        credential=next(x for x in json.loads(Path(__file__).with_name('wms-simulator-credentials.json').read_text('utf-8')) if x['app_key']==connection['app_key'] and x['customer_id']==connection['customer_id'])
        result=current['return_result'];assert result['dispatch_state']=='ACCEPTED',result.get('last_error')
        root=ET.Element('request');head=ET.SubElement(root,'returnOrder')
        for k,v in {'returnOrderCode':c['return_sn'],'returnOrderId':result['external_order'],'warehouseCode':result['external_warehouse'],'ownerCode':result['external_owner'],'outBizCode':'SIM_RETURN_'+c['case_id'],'orderType':'HHRK','confirmType':'0'}.items():ET.SubElement(head,k).text=str(v)
        item=ET.SubElement(ET.SubElement(root,'orderLines'),'orderLine')
        for k,v in {'orderLineNo':'1','itemCode':c['sku'],'ownerCode':result['external_owner'],'actualQty':2,'inventoryType':'ZP','batchCode':'EXCH_RETURN_'+c['case_id']}.items():ET.SubElement(item,k).text=str(v)
        body=ET.tostring(root,encoding='unicode');save(OUT/'return-receipt.json',{'return_sn':c['return_sn'],'body':body})
        def callback():
            p={'app_key':connection['app_key'],'customerId':connection['customer_id'],'method':'taobao.qimen.returnorder.confirm','v':'2.0','format':'xml','sign_method':'md5','timestamp':datetime.now().strftime('%Y-%m-%d %H:%M:%S')};p['sign']=sign(p,body,credential['secret']);url=BASE+'/prod-api/supplychain/wmsCallback/qm/'+connection['callback_key']+'?'+urllib.parse.urlencode(p)
            op=urllib.request.build_opener(urllib.request.ProxyHandler({}));req=urllib.request.Request(url,data=body.encode(),headers={'Content-Type':'application/xml;charset=UTF-8'},method='POST')
            with op.open(req,timeout=40) as response:reply=ET.fromstring(response.read())
            assert reply.findtext('flag')=='success',reply.findtext('message')
        # Keep the simulated WMS query result consistent with its actual receipt, before notifying OMS.
        local_event={'customer':connection['customer_id'],'owner':result['external_owner'],'warehouse':result['external_warehouse'],'sn':c['return_sn'],'external':result['external_order'],'hash':hashlib.sha256(body.encode()).hexdigest()}
        script='''import json,sqlite3,datetime
from contextlib import closing
p=json.loads(REPLACE_PAYLOAD)
key=(p['customer'],'return',p['owner'],p['warehouse'],p['sn'])
with closing(sqlite3.connect('/data/documents.sqlite',timeout=10)) as db,db:
 db.row_factory=sqlite3.Row;db.execute('BEGIN IMMEDIATE')
 row=db.execute('SELECT * FROM documents WHERE customer=? AND kind=? AND owner=? AND warehouse=? AND sn=?',key).fetchone()
 assert row and row['external_id']==p['external'] and row['quantity']==2 and row['status'] in ('ACCEPT','FULFILLED')
 old=db.execute("SELECT body_hash FROM events WHERE customer=? AND kind='return' AND sn=? AND action='RETURN_CONFIRM'",(p['customer'],p['sn'])).fetchall()
 assert not old or all(v['body_hash']==p['hash'] for v in old)
 if not old:
  db.execute("UPDATE documents SET status='FULFILLED' WHERE customer=? AND kind=? AND owner=? AND warehouse=? AND sn=?",key)
  db.execute("INSERT INTO events(customer,kind,sn,action,body_hash,created_at) VALUES (?,'return',?,'RETURN_CONFIRM',?,?)",(p['customer'],p['sn'],p['hash'],datetime.datetime.now(datetime.timezone.utc).isoformat()))
'''.replace('REPLACE_PAYLOAD',repr(json.dumps(local_event)))
        simulated=subprocess.run(['wsl.exe','--','docker','exec','-i','oms-local-wms-simulator','python','-'],input=script.encode(),capture_output=True,timeout=30)
        assert simulated.returncode==0,'Simulated return identity or completion event mismatch'
        callback();before=sql("SELECT total_stock,available_stock,reserved_stock,version FROM oms_inventory WHERE company_code='QM' AND sku_sn="+quote(c['sku']),'qm_oms_saas_inventory');callback();assert before==sql("SELECT total_stock,available_stock,reserved_stock,version FROM oms_inventory WHERE company_code='QM' AND sku_sn="+quote(c['sku']),'qm_oms_saas_inventory')
        c['return_replay_unchanged']=True;save(manifest,c)
        current=exchange()
        if current['platform_status']=='2':
            modified=max(datetime.now()+timedelta(seconds=2),datetime.strptime(current['source_modified'],'%Y-%m-%d %H:%M:%S')+timedelta(seconds=1)).strftime('%Y-%m-%d %H:%M:%S');sql(f"UPDATE channel_exchange_sim SET payload=JSON_SET(payload,'$.status','3','$.modified',{quote(modified)}),modified={quote(modified)} WHERE company_code='qm' AND channel_id={c['channel_id']} AND dispute_id={quote(c['dispute_id'])}",CHANNEL);act('refresh')
        if exchange()['return_qc']!='PASSED':act('qc',{'reason':'本地模拟验收：退回2件，实收身份及数量已核对'})
        current=exchange()
        if current['platform_status']=='3' and current['platform_operation']=='NONE':act('execute',{'operation':'RECEIVED','reason':'本地模拟：实际退回库存已记账，验收已确认'})
        wait_for(exchange,lambda e:e['platform_status'] in ('12','13'),'Platform receipt confirmation incomplete')
        if not exchange().get('fulfillment_order_id'):act('advance')
        current=wait_for(exchange,lambda e:bool(e.get('fulfillment_order_id')),'Replacement order missing')
        c['replacement_order_id']=current['fulfillment_order_id'];save(manifest,c)
        replacement=allocate_and_dispatch(c['replacement_order_id']);replacement=ship(replacement);c['replacement_shipment_sn']=replacement['shipment_sn'];save(manifest,c)
        current=exchange()
        if current['platform_status']=='12' and current['platform_operation']=='NONE':act('execute',{'operation':'CONSIGN','reason':'本地模拟：换出2件已实际出库，使用仓库运单回传'})
        completed=wait_for(exchange,lambda e:e['state']=='COMPLETED','Platform replacement consignment not confirmed')
        old=business(c['order_id']);new=business(c['replacement_order_id']);assert old['shipped_quantity']==10 and old['lines'][0]['sku_sn']==c['sku'] and old['lines'][0]['quantity']==10
        assert new['business_type']=='EXCHANGE' and new['shipped_quantity']==2 and new['stock_reference']=='EXCHANGE:'+str(c['exchange_id'])
        logs=api('/supplychain/wmsIntegration/log-center?numberType=DOCUMENT&number='+c['return_sn']+'&allTime=true')['data']['rows'];assert any(x['action']=='RETURN_CREATE' for x in logs) and any(x['action']=='RETURN_RECEIPT' for x in logs) and all(x['business_type']=='SALE_RETURN' for x in logs)
        c['completed']=True;c['business_url']=BASE+'/oms-order/exchanges?exchangeId='+str(c['exchange_id']);save(manifest,c)
        if not c.get('pending_exchange_id'):
            pending_dispute=c['dispute_id'][:-2]+'02';modified=datetime.now().strftime('%Y-%m-%d %H:%M:%S')
            pending={'dispute_id':pending_dispute,'biz_order_id':c['platform_oid'],'num':9,'status':'1','modified':modified,'exchange_sku':'[验收] 数量超过剩余可换数量，保留人工核对','speed_exchange':0,'refund_phase':'yes'}
            stock_before=sql("SELECT sku_sn,total_stock,available_stock,reserved_stock,version FROM oms_inventory WHERE company_code='QM' AND sku_sn IN ("+quote(c['sku'])+','+quote(c['target_sku'])+") ORDER BY sku_sn",'qm_oms_saas_inventory')
            sql(f"INSERT IGNORE INTO channel_exchange_sim(company_code,channel_id,dispute_id,payload,modified) VALUES ('qm',{c['channel_id']},{quote(pending_dispute)},{quote(json.dumps(pending,ensure_ascii=False))},{quote(modified)})",CHANNEL)
            pending_row=wait_for(lambda:api('/order/exchanges?number='+pending_dispute)['rows'],lambda rows:len(rows)==1,'Pending acceptance application was not synchronized',120)[0]
            assert {'SKU_UNMATCHED','QUANTITY_MISMATCH'}<=set(pending_row['problem_codes'].split(',')) and pending_row['state']=='NEEDS_REVIEW'
            assert stock_before==sql("SELECT sku_sn,total_stock,available_stock,reserved_stock,version FROM oms_inventory WHERE company_code='QM' AND sku_sn IN ("+quote(c['sku'])+','+quote(c['target_sku'])+") ORDER BY sku_sn",'qm_oms_saas_inventory')
            c['pending_exchange_id']=pending_row['id'];c['pending_exchange_sn']=pending_row['exchange_sn'];save(manifest,c)
        save(OUT/'verification.json',{'exchange_sn':completed['exchange_sn'],'exchange_id':c['exchange_id'],'order_sn':old['order_sn'],'replacement_order_sn':new['order_sn'],'original_outbound_quantity':10,'return_quantity':2,'replacement_outbound_quantity':2,'duplicate_return_stock_unchanged':True,'real_platform_writes':False,'warehouse_logs_integrated':True})
        with sync_playwright() as p:
            browser=p.chromium.launch(headless=True);ctx=browser.new_context(viewport={'width':1700,'height':1100});ctx.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=ctx.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)));page.goto(c['business_url'],wait_until='networkidle');expect(page.get_by_text('换货处理详情',exact=True)).to_be_visible();expect(page.get_by_text(completed['exchange_sn'],exact=True).last).to_be_visible();page.screenshot(path=str(OUT/'exchange-detail.png'),full_page=True);assert not errors,errors;browser.close()
        print(json.dumps({'exchange_id':c['exchange_id'],'exchange_sn':completed['exchange_sn'],'completed':True,'return_replay_unchanged':True,'real_platform_writes':False},ensure_ascii=False),flush=True)
    finally:request('/auth/logout',token=token,method='DELETE')


if __name__=='__main__':main()
