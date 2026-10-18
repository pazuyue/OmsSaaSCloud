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
OUT=ROOT/'outputs/refund-parcel-append'
CASE_KIND='refundappend'
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
        case=Case(CASE_KIND,token);c=case.c
        if not c.get('prepared'):
            if not c.get('sku_shortened'):
                c.update(sku='RPK_'+c['case_id']+'_A',target_sku='RPK_'+c['case_id']+'_B',seller='RPK_'+c['case_id'],sku_shortened=True);c['warehouses'][0]['code']='RPK_'+c['case_id']+'_V';case.save()
            c['channel_name']='[自动合包追加验收] '+c['case_id'];fixture.MANIFEST=case.path;fixture.prepare_case(c,token);c['prepared']=True;case.save()
        if not c.get('original_shipped'):
            shipment=case.dispatch_ship(case.allocate()['shipments'][0]);c.update(original_shipped=True,original_shipment_id=shipment['id']);case.save()
        # A dedicated TEST connection keeps capability changes scoped to this fixture's receiving warehouse.
        connection=next(x for x in api('/supplychain/wmsIntegration/connections') if x['id']==c['connection_id'])
        credential=next(x for x in json.loads((ROOT/'docker/local/wms-simulator-credentials.json').read_text('utf-8')) if x['app_key']==connection['app_key'] and x['customer_id']==connection['customer_id'])
        if not c.get('parcel_connection'):
            assert connection['environment']=='TEST' and connection['endpoint']=='http://wms-simulator:8080/qimen/gateway'
            body={k:connection[k] for k in ['provider','api_version','environment','endpoint','app_key','customer_id']};body.update(name='[自动追加验收] '+c['case_id'],secret=credential['secret'],enabled=1,shared_return_waybill=1,shared_return_append=1)
            c['parcel_connection']=api('/supplychain/wmsIntegration/connections',body);case.save()
        if int(api('/supplychain/simulationStore/'+str(c['warehouses'][0]['id']))['connectionId'])!=int(c['parcel_connection']):
            w=api('/supplychain/simulationStore/'+str(c['warehouses'][0]['id']))
            request('/supplychain/simulationStore',token=token,method='PUT',payload={'id':w['id'],'wmsSimulationCode':w['wmsSimulationCode'],'wmsSimulationName':w['wmsSimulationName'],'ownerWarehouseId':w['ownerWarehouseId'],'inboundMode':w['inboundMode'],'outboundMode':w['outboundMode'],'connectionId':c['parcel_connection'],'externalWarehouse':w['externalWarehouse'],'externalOwner':w['externalOwner'],'status':w['status']})
        connection=next(x for x in api('/supplychain/wmsIntegration/connections') if x['id']==c['parcel_connection'])
        assert connection['environment']=='TEST' and connection['endpoint']=='http://wms-simulator:8080/qimen/gateway' and int(connection['shared_return_waybill'])==1
        sql('CREATE DATABASE IF NOT EXISTS '+SOURCE+' CHARACTER SET utf8mb4','ry-cloud')
        sql('CREATE TABLE IF NOT EXISTS jdp_tb_refund(refund_id DECIMAL(20,0) PRIMARY KEY,tid DECIMAL(20,0),oid DECIMAL(20,0),status VARCHAR(64),seller_nick VARCHAR(200),created DATETIME,modified DATETIME,jdp_modified DATETIME,jdp_response MEDIUMTEXT,KEY seller_cursor(seller_nick,jdp_modified,refund_id))',SOURCE)
        sources=api('/order/orders/sources');source=next((s for s in sources if s['source_name']=='[追加退款验收] '+c['case_id']),None)
        if source is None:
            env=dict(line.split('=',1) for line in (ROOT/'docker/local/.env').read_text().splitlines() if '=' in line and not line.startswith('#'))
            api('/order/orders/sources',{'source_name':'[追加退款验收] '+c['case_id'],'host':'mysql','port':3306,'database_name':SOURCE,'username':'root','password':env['MYSQL_ROOT_PASSWORD'],'enabled':1})
            source=next(s for s in api('/order/orders/sources') if s['source_name']=='[追加退款验收] '+c['case_id'])
        configs=api('/order/refunds/configs')['configs']
        if not any(x['channel_id']==c['channel_id'] for x in configs):api('/order/refunds/configs',{'channel_id':c['channel_id'],'source_key':source['source_key'],'seller_nick':c['seller'],'initial_time':c['created'],'revision':0,'enabled':0,'auto_convert':0,'interval_seconds':30})

        def configure(**updates):
            config=api('/order/refunds/parcel-configs/'+str(c['channel_id']))['policy'];config.update(enabled=1,manual_enabled=1,scope='SAME_ORDER',allow_append=1,max_refunds=10,max_quantity=10,auto_enabled=1,auto_append=1,auto_dispatch=1,auto_allocate=1,logistics_sources=['PLATFORM','MANUAL'],store_codes=[c['warehouses'][0]['code']]);config.update(updates);api('/order/refunds/parcel-configs/'+str(c['channel_id']),config)
        def capability(append):
            row=next(x for x in api('/supplychain/wmsIntegration/connections') if x['id']==c['parcel_connection']);row.update(shared_return_append=append);api('/supplychain/wmsIntegration/connections',row)
        def plans(b):return api('/order/refunds/business/'+str(b['id'])+'/goods')['plans']
        def run(b):api('/order/refunds/business/'+str(b['id'])+'/parcels/auto-run',{})
        def stable(b):
            t=plans(b)[0]['actual'];return {k:t[k] for k in ['id','return_sn','quantity','payload_hash','external_order','attempts']}
        capability(1)
        if not c.get('parcel_policy_configured'):configure();c['parcel_policy_configured']=True;case.save()
        if not c.get('seeded_refunds'):
            ids=[]
            for index in range(1,5):
                rid='998'+c['case_id']+str(index).zfill(2);status='WAIT_SELLER_CONFIRM_GOODS';modified=(datetime.strptime(c['created'],'%Y-%m-%d %H:%M:%S')+timedelta(seconds=1)).strftime('%Y-%m-%d %H:%M:%S')
                # The second application initially has no logistics: exercise logistics-arrives-later trigger.
                p={'refund_id':rid,'tid':c['platform_tid'],'oid':c['platform_oid'],'seller_nick':c['seller'],'status':status,'refund_fee':'1.00','num':1,'has_good_return':True,'dispute_type':'REFUND_AND_RETURN','refund_phase':'aftersale','refund_version':'1','reason':'[验收] 已有包裹追加新THK','modified':modified,'company_name':'' if index==2 else '顺丰速运','sid':'' if index==2 else 'TEST_APPEND_'+c['case_id']}
                values=[quote(x) for x in [rid,c['platform_tid'],c['platform_oid'],status,c['seller'],c['created'],modified,modified,json.dumps({'refund_get_response':{'refund':p}},ensure_ascii=False)]]
                sql('INSERT IGNORE INTO jdp_tb_refund(refund_id,tid,oid,status,seller_nick,created,modified,jdp_modified,jdp_response) VALUES ('+','.join(values)+')',SOURCE);ids.append(rid)
            task=api('/order/refunds/sync',{'channel_id':c['channel_id'],'mode':'IDS','refund_ids':ids,'request_key':'APPEND_REFUNDS_'+c['case_id']})
            wait_for(lambda:api('/order/refunds/tasks/'+str(task['id'])),lambda t:t['state']=='COMPLETED','Refund intake incomplete',150);c['seeded_refunds']=ids;case.save()
        if not c.get('refunds'):c['refunds']=[];case.save()
        for index,rid in enumerate(c['seeded_refunds']):
            if index>=len(c['refunds']):
                if index==2:configure(auto_append=0)
                if index==3:capability(0)
                row=api('/order/refunds/platform?'+urllib.parse.urlencode({'number':rid}))['rows'][0];converted=api('/order/refunds/platform/'+str(row['id'])+'/convert',{});b=api('/order/refunds/business/'+str(converted['id']))
                api('/order/refunds/business/'+str(b['id'])+'/review',{'control_version':b['control_version'],'platform_version':b['platform']['version'],'purpose':'CANCEL','goods_action':'RETURN','reason':'[验收] 原订单来源不变、退回单独立','evidence':'TEST-AUTO-APPEND'})
                b=api('/order/refunds/business/'+str(b['id']));s=next(s for s in b['shipments'] if s['shipment_id']==c['original_shipment_id'])
                plan=api('/order/refunds/business/'+str(b['id'])+'/goods/plans',{'control_version':b['control_version'],'shipment_line_id':s['shipment_line_id'],'quantity':1,'store_code':c['warehouses'][0]['code'],'return_address':'[验收] 无实际寄送的追加退回地址','return_contact':'TEST-APPEND','reason':'[验收] 明确实际来源和数量','evidence':'TEST-RETURN-SOURCE','confirmed':True,'request_key':'APPEND_'+c['case_id']+'_'+str(index)})
                entry={'id':b['id'],'refund_sn':b['refund_sn'],'platform_id':row['id'],'plan_id':plan['id']};c['refunds'].append(entry);case.save()
            entry=c['refunds'][index];b=api('/order/refunds/business/'+str(entry['id']))
            if index==0:
                run(entry)
                p=plans(entry)[0];assert p['parcel_state']=='LINKED' and p['actual']['dispatch_state']=='ACCEPTED';parcel=api('/order/refunds/business/'+str(b['id'])+'/parcels')[0];c.update(parcel_id=parcel['id'],parcel_sn=parcel['parcel_sn']);snapshot=stable(entry);assert not c.get('original_snapshot') or snapshot==c['original_snapshot'];c.setdefault('original_snapshot',snapshot);case.save()
            if index==1 and not c.get('late_logistics_verified'):
                if not c.get('logistics_wait_verified'):
                    assert plans(entry)[0]['parcel_state']=='WAIT_LOGISTICS' and not plans(entry)[0]['actual'];c['logistics_wait_verified']=True;case.save()
                raw=json.loads(sql('SELECT jdp_response FROM jdp_tb_refund WHERE refund_id='+quote(rid),SOURCE));p=raw['refund_get_response']['refund'];p.update(company_name='顺丰速运',sid='TEST_APPEND_'+c['case_id'],refund_version='2',modified=datetime.now().strftime('%Y-%m-%d %H:%M:%S'))
                sql('UPDATE jdp_tb_refund SET jdp_response='+quote(json.dumps(raw,ensure_ascii=False))+',modified='+quote(p['modified'])+',jdp_modified='+quote(p['modified'])+' WHERE refund_id='+quote(rid),SOURCE)
                task=api('/order/refunds/sync',{'channel_id':c['channel_id'],'mode':'IDS','refund_ids':[rid],'request_key':'APPEND_LOGISTICS_'+c['case_id']});wait_for(lambda:api('/order/refunds/tasks/'+str(task['id'])),lambda t:t['state']=='COMPLETED','Logistics update incomplete',150)
                wait_for(lambda:plans(entry)[0],lambda p:p['actual'] and p['actual']['dispatch_state']=='ACCEPTED','Late logistics did not automatically append and dispatch',90)
                assert stable(c['refunds'][0])==c['original_snapshot'];parcel=api('/order/refunds/business/'+str(b['id'])+'/parcels')[0];assert parcel['id']==c['parcel_id'] and len(parcel['members'])>=2;c['late_logistics_verified']=True;case.save()
            if index==2 and not c.get('shop_block_verified'):
                assert plans(entry)[0]['parcel_state']=='NEED_CHECK' and not plans(entry)[0]['actual'];assert not api('/order/refunds/business/'+str(b['id'])+'/parcels');c['shop_block_verified']=True;case.save();configure();run(entry);wait_for(lambda:plans(entry)[0],lambda p:p['actual'] and p['actual']['dispatch_state']=='ACCEPTED','New THK dispatch not ready',60)
            if index==3 and not c.get('warehouse_block_verified'):
                assert plans(entry)[0]['parcel_state']=='NEED_CHECK' and not plans(entry)[0]['actual'];assert not api('/order/refunds/business/'+str(b['id'])+'/parcels');c['warehouse_block_verified']=True;case.save()
        assert stable(c['refunds'][0])==c['original_snapshot']
        for b in c['refunds'][:3]:
            run(b);run(b);assert plans(b)[0]['actual']['dispatch_state']=='ACCEPTED'
            rows=api('/supplychain/wmsIntegration/log-center?'+urllib.parse.urlencode({'numberType':'REFUND','number':b['refund_sn'],'allTime':'true','action':'REFUND_RETURN_CREATE','result':'SUCCESS'}))['rows'];assert len(rows)==1,rows
            wire=ET.fromstring(api('/supplychain/wmsIntegration/logs/'+str(rows[0]['id']))['request_body']);assert wire.findtext('returnOrder/logisticsCode')=='SF' and wire.findtext('returnOrder/expressCode')=='TEST_APPEND_'+c['case_id'];assert c['parcel_sn'] in wire.findtext('returnOrder/remark')
        if not c.get('receipt_frozen'):
            assert stock_quantity(c)==0
            p=plans(c['refunds'][0])[0]['actual'];root=ET.Element('request');head=ET.SubElement(root,'returnOrder')
            for k,v in {'returnOrderCode':c['parcel_sn'],'returnOrderId':'TEST_APPEND_PARCEL_'+c['case_id'],'warehouseCode':p['external_warehouse'],'ownerCode':p['external_owner'],'outBizCode':'APPEND_FREEZE','orderType':'THRK','confirmType':'0'}.items():ET.SubElement(head,k).text=str(v)
            line=ET.SubElement(ET.SubElement(root,'orderLines'),'orderLine')
            for k,v in {'itemCode':c['sku'],'ownerCode':p['external_owner'],'actualQty':1,'inventoryType':'ZP','batchCode':'TEST_APPEND'}.items():ET.SubElement(line,k).text=str(v)
            body=ET.tostring(root,encoding='unicode');params={'app_key':connection['app_key'],'customerId':connection['customer_id'],'method':'taobao.qimen.returnorder.confirm','v':'2.0','format':'xml','sign_method':'md5','timestamp':datetime.now().strftime('%Y-%m-%d %H:%M:%S')};params['sign']=sign(params,body,credential['secret']);req=urllib.request.Request(BASE+'/prod-api/supplychain/wmsCallback/qm/'+connection['callback_key']+'?'+urllib.parse.urlencode(params),data=body.encode(),headers={'Content-Type':'application/xml;charset=UTF-8'},method='POST')
            with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req,timeout=50) as response:reply=ET.fromstring(response.read())
            assert reply.findtext('flag')=='success',reply.findtext('message');c['receipt_frozen']=True;case.save()
        capability(1);run(c['refunds'][3]);fourth=plans(c['refunds'][3])[0];assert fourth['parcel_state']=='NEED_CHECK' and not fourth['actual'];assert not api('/order/refunds/business/'+str(c['refunds'][3]['id'])+'/parcels');assert stock_quantity(c)==0
        for member in c['refunds'][:3]:api('/order/refunds/business/'+str(member['id'])+'/goods/refresh',{})
        parcel=api('/order/refunds/business/'+str(c['refunds'][0]['id'])+'/parcels')[0];assert len(parcel['members'])==3;assert any(e['state']=='WAIT_ALLOCATION' for e in parcel['receipts'])
        with sync_playwright() as pw:
            browser=pw.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1600,'height':1100});context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=context.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
            page.goto(BASE+'/oms-order/management?'+urllib.parse.urlencode({'channelId':c['channel_id'],'action':'parcels'}),wait_until='networkidle');dialog=page.get_by_role('dialog',name='退回包裹 · 合包与自动处理',exact=True);expect(dialog).to_be_visible();expect(dialog.get_by_text('允许下发后追加THK',exact=True)).to_be_visible();page.screenshot(path=str(OUT/'auto-parcel-settings.png'),animations='disabled');dialog.get_by_text('仓库能力与生效检查',exact=True).scroll_into_view_if_needed();page.screenshot(path=str(OUT/'warehouse-capability-check.png'),animations='disabled');revision=api('/order/refunds/parcel-configs/'+str(c['channel_id']))['policy']['revision'];dialog.get_by_role('button',name='保存合包配置',exact=True).click();expect(dialog).not_to_be_visible();assert api('/order/refunds/parcel-configs/'+str(c['channel_id']))['policy']['revision']==revision+1
            page.goto(BASE+'/oms-order/businessRefunds?id='+str(c['refunds'][2]['id']),wait_until='networkidle');dialog=page.get_by_role('dialog',name='业务退款详情 · 退货退款',exact=True);expect(dialog).to_be_visible();dialog.get_by_role('button',name='处理货物与退回单',exact=True).click();panel=dialog.locator('.refund-parcels');panel.scroll_into_view_if_needed();expect(panel.get_by_text('合包退回 · 3 张退款单',exact=True)).to_be_visible();page.screenshot(path=str(OUT/'appended-thks.png'),animations='disabled');assert not errors,errors;browser.close()
        evidence={'parcel_sn':c['parcel_sn'],'waybill':'TEST_APPEND_'+c['case_id'],'refunds':c['refunds'],'total_thks':3,'newly_appended_thks':2,'ui_config_saved':True,'original_thk_unchanged':True,'duplicate_dispatches':0,'late_logistics_auto_linked':True,'shop_append_disabled_blocked':True,'warehouse_append_disabled_blocked':True,'after_receipt_new_scope_blocked':True,'ambiguous_receipt':'WAIT_ALLOCATION','stock_added':0,'real_platform_writes':False,'url':BASE+'/oms-order/businessRefunds?id='+str(c['refunds'][2]['id'])};(OUT/'case.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf-8');print('PASS automatic shared parcel: late platform logistics appended new THK; original unchanged; repeated triggers safe; shop/warehouse capabilities and receipt freeze enforced')
    finally:request('/auth/logout',token=token,method='DELETE')

if __name__=='__main__':main()
