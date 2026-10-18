"""Retained TEST acceptance: three TK applications, one carrier waybill, explicit shared-SKU receipt allocation.

Uses isolated synthetic JDP rows and the local signed WMS simulator only. No platform write/payment.
"""
import json
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime,timedelta
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE
from test_multi_virtual_shipping import sql,quote,wait_for
from verify_exchange_ownership import Case
import verify_allocation_rules_v2 as fixture
from wms_qimen_simulator import sign

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'outputs/refund-shared-thk'
ORDER='qm_oms_saas_order'
SUPPLY='qm_oms_saas_commodity'
SOURCE='refund_acceptance_source'

def stock_quantity(c):return int(sql("SELECT total_stock FROM oms_inventory WHERE company_code='QM' AND sku_sn="+quote(c['sku']),'qm_oms_saas_inventory'))

def main():
    OUT.mkdir(parents=True,exist_ok=True);token=login()
    def api(path,body=None):
        response=request(path,token=token,payload=body,method='POST' if body is not None else 'GET');return response.get('data',response)
    try:
        def ready():
            try:return api('/supplychain/wmsIntegration/connections') if api('/order/refunds/catalog') is not None else None
            except (AssertionError,OSError):return None
        wait_for(ready,lambda r:r is not None,'Refund parcel services not ready',180)
        case=Case('refundsharedthk',token);c=case.c
        if not c.get('prepared'):
            if not c.get('sku_shortened'):
                c.update(sku='RPK_'+c['case_id']+'_A',target_sku='RPK_'+c['case_id']+'_B',seller='RPK_'+c['case_id'],sku_shortened=True);c['warehouses'][0]['code']='RPK_'+c['case_id']+'_V';case.save()
            c['channel_name']='[一包一THK验收] '+c['case_id'];fixture.MANIFEST=case.path;fixture.prepare_case(c,token);c['prepared']=True;case.save()
        if not c.get('original_shipped'):
            shipment=case.dispatch_ship(case.allocate()['shipments'][0]);c.update(original_shipped=True,original_shipment_id=shipment['id']);case.save()
        # A dedicated TEST connection keeps capability changes scoped to this fixture's receiving warehouse.
        connection=next(x for x in api('/supplychain/wmsIntegration/connections') if x['id']==c['connection_id'])
        credential=next(x for x in json.loads((ROOT/'docker/local/wms-simulator-credentials.json').read_text('utf-8')) if x['app_key']==connection['app_key'] and x['customer_id']==connection['customer_id'])
        if not c.get('parcel_connection'):
            assert connection['environment']=='TEST' and connection['endpoint']=='http://wms-simulator:8080/qimen/gateway'
            body={k:connection[k] for k in ['provider','api_version','environment','endpoint','app_key','customer_id']};body.update(name='[合包验收] '+c['case_id'],secret=credential['secret'],enabled=1,shared_return_waybill=1)
            c['parcel_connection']=api('/supplychain/wmsIntegration/connections',body);case.save()
        if int(api('/supplychain/simulationStore/'+str(c['warehouses'][0]['id']))['connectionId'])!=int(c['parcel_connection']):
            w=api('/supplychain/simulationStore/'+str(c['warehouses'][0]['id']))
            request('/supplychain/simulationStore',token=token,method='PUT',payload={'id':w['id'],'wmsSimulationCode':w['wmsSimulationCode'],'wmsSimulationName':w['wmsSimulationName'],'ownerWarehouseId':w['ownerWarehouseId'],'inboundMode':w['inboundMode'],'outboundMode':w['outboundMode'],'connectionId':c['parcel_connection'],'externalWarehouse':w['externalWarehouse'],'externalOwner':w['externalOwner'],'status':w['status']})
        connection=next(x for x in api('/supplychain/wmsIntegration/connections') if x['id']==c['parcel_connection'])
        assert connection['environment']=='TEST' and connection['endpoint']=='http://wms-simulator:8080/qimen/gateway' and int(connection['shared_return_waybill'])==1
        sql('CREATE DATABASE IF NOT EXISTS '+SOURCE+' CHARACTER SET utf8mb4','ry-cloud')
        sql('CREATE TABLE IF NOT EXISTS jdp_tb_refund(refund_id DECIMAL(20,0) PRIMARY KEY,tid DECIMAL(20,0),oid DECIMAL(20,0),status VARCHAR(64),seller_nick VARCHAR(200),created DATETIME,modified DATETIME,jdp_modified DATETIME,jdp_response MEDIUMTEXT,KEY seller_cursor(seller_nick,jdp_modified,refund_id))',SOURCE)
        sources=api('/order/orders/sources');source=next((s for s in sources if s['source_name']=='[合包退款验收] '+c['case_id']),None)
        if source is None:
            env=dict(line.split('=',1) for line in (ROOT/'docker/local/.env').read_text().splitlines() if '=' in line and not line.startswith('#'))
            api('/order/orders/sources',{'source_name':'[合包退款验收] '+c['case_id'],'host':'mysql','port':3306,'database_name':SOURCE,'username':'root','password':env['MYSQL_ROOT_PASSWORD'],'enabled':1})
            source=next(s for s in api('/order/orders/sources') if s['source_name']=='[合包退款验收] '+c['case_id'])
        configs=api('/order/refunds/configs')['configs']
        if not any(x['channel_id']==c['channel_id'] for x in configs):api('/order/refunds/configs',{'channel_id':c['channel_id'],'source_key':source['source_key'],'seller_nick':c['seller'],'initial_time':c['created'],'revision':0,'enabled':0,'auto_convert':0,'interval_seconds':30})
        if not c.get('seeded_refunds'):
            ids=[]
            for index in range(1,4):
                rid='997'+c['case_id']+str(index).zfill(2);status='SUCCESS' if index==1 else 'WAIT_SELLER_CONFIRM_GOODS';modified=(datetime.strptime(c['created'],'%Y-%m-%d %H:%M:%S')+timedelta(seconds=1)).strftime('%Y-%m-%d %H:%M:%S')
                p={'refund_id':rid,'tid':c['platform_tid'],'oid':c['platform_oid'],'seller_nick':c['seller'],'status':status,'refund_fee':'2.00' if index==1 else '1.00','num':2 if index==1 else 1,'has_good_return':True,'dispute_type':'REFUND_AND_RETURN','refund_phase':'aftersale','refund_version':'1','reason':'[验收] 多退款同SKU合包寄回','modified':modified,'company_name':'SF','sid':'TEST_SHARED_'+c['case_id']}
                values=[quote(x) for x in [rid,c['platform_tid'],c['platform_oid'],status,c['seller'],c['created'],modified,modified,json.dumps({'refund_get_response':{'refund':p}},ensure_ascii=False)]]
                sql('INSERT IGNORE INTO jdp_tb_refund(refund_id,tid,oid,status,seller_nick,created,modified,jdp_modified,jdp_response) VALUES ('+','.join(values)+')',SOURCE);ids.append(rid)
            task=api('/order/refunds/sync',{'channel_id':c['channel_id'],'mode':'IDS','refund_ids':ids,'request_key':'PARCEL_REFUNDS_'+c['case_id']})
            wait_for(lambda:api('/order/refunds/tasks/'+str(task['id'])),lambda t:t['state']=='COMPLETED','Synthetic refund intake incomplete',150)
            c['seeded_refunds']=ids;case.save()
        if not c.get('refunds'):c['refunds']=[];case.save()
        for index,rid in enumerate(c['seeded_refunds']):
            if index<len(c['refunds']):continue
            row=api('/order/refunds/platform?'+urllib.parse.urlencode({'number':rid}))['rows'][0];result=api('/order/refunds/platform/'+str(row['id'])+'/convert',{});assert result['state']=='SUCCESS'
            b=api('/order/refunds/business/'+str(result['id']))
            api('/order/refunds/business/'+str(b['id'])+'/review',{'control_version':b['control_version'],'platform_version':b['platform']['version'],'purpose':'CANCEL','goods_action':'RETURN','reason':'[验收] 退货退款独立审核，实物合包','evidence':'TEST-SHARED-PARCEL'})
            b=api('/order/refunds/business/'+str(b['id']));s=next(s for s in b['shipments'] if s['shipment_id']==c['original_shipment_id'])
            plan=api('/order/refunds/business/'+str(b['id'])+'/goods/plans',{'control_version':b['control_version'],'shipment_line_id':s['shipment_line_id'],'quantity':2 if index==0 else 1,'store_code':c['warehouses'][0]['code'],'return_address':'[验收] 无实际寄送的合包退回地址','return_contact':'TEST-PARCEL','reason':'[验收] 明确实际来源和数量','evidence':'TEST-RETURN-SOURCE','confirmed':True,'request_key':'SHARED_'+c['case_id']+'_'+str(index)})
            c['refunds'].append({'id':b['id'],'refund_sn':b['refund_sn'],'plan_id':plan['id']});case.save()
        def plans(b):return api('/order/refunds/business/'+str(b['id'])+'/goods')['plans']
        if not c.get('parcel_id'):
            b=c['refunds'][0];p=plans(b)[0];detail=api('/order/refunds/business/'+str(b['id']))
            parcel=api(f"/order/refunds/business/{b['id']}/parcels/plans/{p['id']}",{'control_version':detail['control_version'],'plan_version':p['control_version'],'carrier_code':'SF','waybill':'TEST_SHARED_'+c['case_id'],'confirmed':True,'reason':'[验收] 同一实际包裹','evidence':'TEST-PARCEL-MANIFEST'})
            c.update(parcel_id=parcel['id'],parcel_sn=parcel['parcel_sn']);case.save()
        for b in c['refunds'][1:]:
            if api('/order/refunds/business/'+str(b['id'])+'/parcels'):continue
            p=plans(b)[0];detail=api('/order/refunds/business/'+str(b['id']))
            api(f"/order/refunds/business/{b['id']}/parcels/plans/{p['id']}",{'control_version':detail['control_version'],'plan_version':p['control_version'],'carrier_code':'SF','waybill':'TEST_SHARED_'+c['case_id'],'join_parcel_id':c['parcel_id'],'confirmed':True,'reason':'[验收] 核实同运单多申请，共用同一实际包裹','evidence':'TEST-PARCEL-MANIFEST'})
        b=c['refunds'][0]
        parcel=api('/order/refunds/business/'+str(b['id'])+'/parcels')[0]
        if not parcel.get('warehouse_document'):
            api(f"/order/refunds/business/{b['id']}/parcels/{c['parcel_id']}/document/dispatch",{'parcel_version':parcel['control_version'],'confirmed_shared':True,'reason':'[验收] 三退款同一包裹，统一下发THK','evidence':'TEST-SHARED-THK'})
        for b in c['refunds']:api('/order/refunds/business/'+str(b['id'])+'/goods/refresh',{})
        parcel=api('/order/refunds/business/'+str(c['refunds'][0]['id'])+'/parcels')[0];doc=parcel['warehouse_document'];assert doc['dispatch_state']=='ACCEPTED',doc
        c['return_sn']=doc['return_sn'];case.save()
        thks={plans(b)[0]['actual']['return_sn'] for b in c['refunds']};assert thks=={doc['return_sn']}
        create_ids=set()
        for b in c['refunds']:
            logs=api('/supplychain/wmsIntegration/log-center?'+urllib.parse.urlencode({'numberType':'REFUND','number':b['refund_sn'],'allTime':'true','action':'REFUND_RETURN_CREATE'}))['rows']
            assert len(logs)==1,logs;create_ids.add(logs[0]['id']);wire=ET.fromstring(api('/supplychain/wmsIntegration/logs/'+str(logs[0]['id']))['request_body'])
            assert wire.findtext('returnOrder/returnOrderCode')==doc['return_sn'];assert len(wire.findall('orderLines/orderLine'))==3
            assert sorted(int(x.findtext('planQty')) for x in wire.findall('orderLines/orderLine'))==[1,1,2]
        assert len(create_ids)==1
        if 'stock_before' not in c:c['stock_before']=stock_quantity(c);case.save()
        if not c.get('pending_receipt'):
            root=ET.Element('request');head=ET.SubElement(root,'returnOrder');source=plans(c['refunds'][0])[0]['actual']
            for k,v in {'returnOrderCode':doc['return_sn'],'returnOrderId':doc['external_order'],'warehouseCode':source['external_warehouse'],'ownerCode':source['external_owner'],'outBizCode':'SHARED_THK_PARTIAL_3','orderType':'THRK','confirmType':'1'}.items():ET.SubElement(head,k).text=str(v)
            line=ET.SubElement(ET.SubElement(root,'orderLines'),'orderLine')
            for k,v in {'itemCode':c['sku'],'ownerCode':source['external_owner'],'actualQty':3,'inventoryType':'ZP','batchCode':'SHARED_THK_TEST'}.items():ET.SubElement(line,k).text=str(v)
            wire=ET.tostring(root,encoding='unicode');params={'app_key':connection['app_key'],'customerId':connection['customer_id'],'method':'taobao.qimen.returnorder.confirm','v':'2.0','format':'xml','sign_method':'md5','timestamp':datetime.now().strftime('%Y-%m-%d %H:%M:%S')};params['sign']=sign(params,wire,credential['secret'])
            for _ in range(2):
                req=urllib.request.Request(BASE+'/prod-api/supplychain/wmsCallback/qm/'+connection['callback_key']+'?'+urllib.parse.urlencode(params),data=wire.encode(),headers={'Content-Type':'application/xml;charset=UTF-8'},method='POST')
                with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req,timeout=50) as response:reply=ET.fromstring(response.read())
                assert reply.findtext('flag')=='success',reply.findtext('message')
            c['pending_receipt']=True;case.save()
        for b in c['refunds']:api('/order/refunds/business/'+str(b['id'])+'/goods/refresh',{})
        parcel=api('/order/refunds/business/'+str(c['refunds'][2]['id'])+'/parcels')[0]
        assert len(parcel['receipts'])==1 and parcel['receipts'][0]['quantity']==3 and parcel['receipts'][0]['state']=='WAIT_ALLOCATION'
        assert stock_quantity(c)==c['stock_before']
        states=[api('/order/refunds/business/'+str(b['id'])) for b in c['refunds']];assert all(b['goods_state']=='WAIT_CHECK' for b in states)
        for b in c['refunds']:
            logs=api('/supplychain/wmsIntegration/log-center?'+urllib.parse.urlencode({'numberType':'REFUND','number':b['refund_sn'],'allTime':'true','action':'REFUND_RETURN_RECEIPT'}))['rows'];assert logs and all(l['ticket_sn']==doc['return_sn'] for l in logs)
        ticket=api('/supplychain/purchaseWorkspace/ticket/'+str(doc['ticket_id']))
        assert ticket['progress']=='ATTENTION' and ticket['sharedPendingQuantity']==3 and ticket['numberActually']==0 and ticket['numberPosted']==0
        assert len(ticket['lines'])==3 and ticket['executionLabel']=='仓库已回传，待确认归属'
        third=states[2]
        with sync_playwright() as pw:
            browser=pw.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1800,'height':1100});context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=context.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
            page.goto(BASE+'/oms-order/businessRefunds?id='+str(third['id']),wait_until='networkidle');dialog=page.get_by_role('dialog',name='业务退款详情 · 退货退款',exact=True);expect(dialog).to_be_visible();dialog.get_by_role('button',name='查看本退款货物进度',exact=True).click()
            dialog.locator('.plan').get_by_role('button',name='查看包裹及处理实收',exact=True).first.click();panel=page.locator('.refund-parcel-drawer');expect(panel).to_be_visible();expect(panel.get_by_text('合包退回 · 3 张退款单',exact=True)).to_be_visible();expect(panel.get_by_text('共同退货入库单 '+doc['return_sn'],exact=True)).to_be_visible();page.screenshot(path=str(OUT/'one-shared-thk.png'),animations='disabled')
            panel.get_by_role('button',name='分配本次实收',exact=True).first.click();review=page.get_by_role('dialog',name='分配本次实收',exact=True);expect(review).to_be_visible();page.screenshot(path=str(OUT/'partial-receipt-review.png'),animations='disabled');review.get_by_role('button',name='返回',exact=True).click()
            panel.get_by_role('button',name='截停共同THK',exact=True).click();scope=page.get_by_role('dialog',name='共同退货入库单处理',exact=True);expect(scope).to_be_visible();assert scope.locator('tbody tr').count()==3;expect(scope.get_by_role('button',name='确认整单截停',exact=True)).to_be_disabled();page.screenshot(path=str(OUT/'shared-cancel-scope.png'),animations='disabled');scope.get_by_role('button',name='返回',exact=True).click();assert not errors,errors;browser.close()
        evidence={'parcel_sn':c['parcel_sn'],'thk':doc['return_sn'],'waybill':'TEST_SHARED_'+c['case_id'],'refunds':[{k:b[k] for k in ['id','refund_sn','state','goods_state','funds_state']} for b in states],'planned_quantity':4,'warehouse_reported':3,'unallocated_quantity':3,'stock_added':0,'create_log_ids':list(create_ids),'url':BASE+'/oms-order/businessRefunds?id='+str(third['id']),'real_platform_writes':False}
        (OUT/'case.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf-8');print('PASS three TK, one parcel, one THK and three source lines; signed THK receipt retained pending, no stock added twice')
    finally:request('/auth/logout',token=token,method='DELETE')

if __name__=='__main__':main()
