"""Owned local gift acceptance. Real OMS/stock APIs, synthetic JDP and TEST WMS only."""
import hashlib
import json
import subprocess
import sys
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta
from pathlib import Path
from verify_goods_workspace_ui import login, request, BASE
from test_multi_virtual_shipping import sql, quote, wait_for
from wms_qimen_simulator import sign

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'outputs/gifts'
ORDER='qm_oms_saas_order'
INVENTORY='qm_oms_saas_inventory'
SOURCE='refund_acceptance_source'


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    token=login()
    def api(path,body=None):
        if path.startswith('/inventory/api/WmsInventory/addInventory'):
            # Exercise the authenticated internal receiving contract inside the local TEST network.
            script="import json,urllib.request\nbody="+repr(body)+"\nr=urllib.request.urlopen(urllib.request.Request('http://inventory:9303'+"+repr(path.replace('/inventory','',1))+",data=json.dumps(body).encode(),headers={'from-source':'inner','Content-Type':'application/json'},method='POST'),timeout=60)\nprint(r.read().decode())"
            call=subprocess.run(['wsl.exe','--','docker','exec','-i','oms-local-wms-simulator','python','-'],input=script.encode(),capture_output=True,timeout=90)
            assert call.returncode==0,call.stderr.decode()[-400:]
            r=json.loads(call.stdout)
        else:r=request(path,token=token,payload=body,method='POST' if body is not None else 'GET')
        assert r.get('code',200)==200,(path,r.get('msg'))
        return r.get('data',r)
    manifest=OUT/'case.json'
    resume='--resume' in sys.argv
    c=json.loads(manifest.read_text('utf-8')) if resume else {'stamp':datetime.now().strftime('%Y%m%d%H%M%S'),'checks':[],'orders':{}}
    def save():manifest.write_text(json.dumps(c,ensure_ascii=False,indent=2),encoding='utf-8')
    def checked(message):
        if message not in c['checks']:c['checks'].append(message)
        save();print('PASS '+message,flush=True)
    def order(o):return api('/order/orders/business/'+str(o['id']))
    def report(o):return api('/order/gifts/orders/'+str(o['id']))
    def gift(o):return report(o)['lines'][0]
    def refund(o):return api('/order/refunds/business/'+str(o['refund_id']))
    def count(sku):return int(sql("SELECT total_stock FROM oms_inventory WHERE company_code='QM' AND sku_sn="+quote(sku),INVENTORY))
    if not c.get('group_id'):
        stamp=c['stamp'];c.update(seller='GIFT_ACCEPT_'+stamp,sku='GIFT_A_'+stamp,gift_sku='GIFT_G_'+stamp,created=datetime.now().strftime('%Y-%m-%d %H:%M:%S'));save()
        if not c.get('channel_id'):api('/channel/channel',{'channelName':'[赠品验收] 串联策略与售后 '+stamp,'channelType':'TM','enabled':1,'toChannelEnabled':1,'syncEnabled':0,'mmodelType':1})
        c['channel_id']=int(sql("SELECT channel_id FROM t_channel WHERE UPPER(company_code)='QM' AND channel_name="+quote('[赠品验收] 串联策略与售后 '+stamp),'qm_oms_saas_channel'));save()
        sample=api('/goods/info/list?pageNum=1&pageSize=1',{})['rows'][0]
        for sku,name in [(c['sku'],'[赠品验收] 购买商品'),(c['gift_sku'],'[赠品验收] OMS 策略赠品')]:
            if not sql("SELECT id FROM goods_sku_sn_info WHERE UPPER(company_code)='QM' AND sku_sn="+quote(sku),'qm_oms_saas_commodity'):api('/goods/info',{'skuSn':sku,'goodsSn':sku,'goodsName':name,'categoryCode':sample['categoryCode'],'colorCode':sample['colorCode'],'sizeCode':sample['sizeCode'],'marketPrice':5,'validity':30,'isPackage':0,'isFd':0})
        w=next(w for w in api('/supplychain/simulationStore/list?pageSize=100')['rows'] if w['wmsSimulationCode']=='VC0001')
        store_id=sql("SELECT id FROM wms_simulation_store_info WHERE UPPER(company_code)='QM' AND wms_simulation_code="+quote('GIFT_V_'+stamp),'qm_oms_saas_commodity')
        if not store_id:store_id=api('/supplychain/simulationStore',{'wmsSimulationCode':'GIFT_V_'+stamp,'wmsSimulationName':'[赠品验收] 独立测试仓 '+stamp,'ownerWarehouseId':w['ownerWarehouseId'],'inboundMode':2,'outboundMode':1,'connectionId':w['connectionId'],'externalWarehouse':w['externalWarehouse'],'externalOwner':w['externalOwner'],'status':2})
        w=api('/supplychain/simulationStore/'+str(store_id))
        connection=next(v for v in api('/supplychain/wmsIntegration/connections') if v['id']==w['connectionId'])
        assert connection['environment']=='TEST' and connection['endpoint']=='http://wms-simulator:8080/qimen/gateway'
        c.update(store=w['wmsSimulationCode'],owner=w['ownerCode'],connection_id=connection['id']);save()
        for sku in [c['sku'],c['gift_sku']]:
            api('/inventory/api/WmsInventory/addInventory?company_code=QM',{'relationSn':'TEST-GIFT-IN-'+sku,'wmsInventoryBatch':{'companyCode':'QM','storeCode':c['store'],'skuSn':sku,'batchCode':'TEST-GIFT-'+stamp,'zpActualNumber':20,'zpAvailableNumber':20,'zpLockNumber':0,'cpActualNumber':0,'cpAvailableNumber':0,'cpLockNumber':0}})
            sql("INSERT IGNORE INTO oms_channel_inventory(company_code,channel_id,sku_sn,quota_stock,stock_scope_json) VALUES ('QM',"+str(c['channel_id'])+','+quote(sku)+',20,'+quote(json.dumps([c['store']]))+')',INVENTORY)
        source=next(v for v in api('/order/orders/sources') if v['database_name']==SOURCE and v['enabled'])
        c['source_key']=source['source_key'];save()
        api('/order/orders/configs',{'channel_id':c['channel_id'],'source_key':c['source_key'],'seller_nick':c['seller'],'confirm_seller_mapping':1,'enabled':0,'auto_convert':1,'allowed_trade_types':['fixed'],'allow_public_stock':1,'interval_seconds':30,'initial_time':c['created']})
        p=api('/order/orders/allocation-config/'+str(c['channel_id']))
        api('/order/orders/allocation-config',{'channel_id':c['channel_id'],'revision':p['revision'],'policy':{'enabled':1,'owner_code':c['owner'],'warehouse_order':[c['store']],'province_priorities':{},'allow_multi':1,'allow_partial':0,'allow_split_sku':1}})
        p=next(v for v in api('/order/orders/carrier-configs') if v['channel_id']==c['channel_id'])
        api('/order/orders/carrier-configs',{'channel_id':c['channel_id'],'revision':p['revision'],'policy':{'enabled':1,'carrier_order':['SF'],'routes':[]}})
        rule={'key':'BUY_TWO','name':'每买两件赠一件','type':'MULTIPLE','minimum':2,'amount':0,'cap':100,'skus':[c['sku']],'gifts':[{'sku_sn':c['gift_sku'],'goods_name':'[赠品验收] OMS 策略赠品','quantity':1}],'tiers':[],'stop':False}
        c['group_id']=api('/order/gifts/groups',{'name':'[验收] 买二赠一及售后 '+stamp,'priority':1,'enabled':1,'policy':{'channels':[c['channel_id']],'start_time':(datetime.now()-timedelta(days=1)).strftime('%Y-%m-%d %H:%M:%S'),'end_time':(datetime.now()+timedelta(days=1)).strftime('%Y-%m-%d %H:%M:%S'),'cross_group_stack':False,'shipped_policy':'RETURN','allow_waive':True,'difference_affects_amount':False,'rules':[rule]}})['id'];save()
        api('/order/refunds/configs',{'channel_id':c['channel_id'],'source_key':c['source_key'],'seller_nick':c['seller'],'initial_time':c['created'],'revision':0,'enabled':0,'auto_convert':1,'interval_seconds':30})
    for index,name in enumerate(['pending','cancel','return'],1):
        if name in c['orders']:continue
        tid='96'+c['stamp']+str(index).zfill(2);oid=tid+'1'
        trade={'tid':tid,'status':'WAIT_SELLER_SEND_GOODS','type':'fixed','seller_nick':c['seller'],'created':c['created'],'modified':c['created'],'pay_time':c['created'],'payment':'25.00','receiver_name':'赠品验收收件人','receiver_mobile':'TEST-MASKED','receiver_state':'北京市','receiver_city':'北京市','receiver_district':'验收区','receiver_address':'验收地址，无实际配送','orders':{'order':[{'oid':oid,'outer_sku_id':c['sku'],'num':5,'payment':'25.00','divide_order_fee':'25.00','title':'[赠品验收] 购买商品'}]}}
        payload=json.dumps({'trade_fullinfo_get_response':{'trade':trade}},ensure_ascii=False)
        sql('INSERT INTO jdp_tb_trade(tid,status,type,seller_nick,created,modified,jdp_modified,jdp_response) VALUES ('+','.join(quote(v) for v in [tid,trade['status'],'fixed',c['seller'],c['created'],c['created'],c['created'],payload])+')',SOURCE)
        api('/order/orders/backfill',{'mode':'TID','channel_ids':[c['channel_id']],'tids':[tid],'request_key':'GIFT_'+c['stamp']+'_'+name})
        def business():
            rows=api('/order/orders/platform/list?tid='+tid)['rows']
            return rows[0] if rows and rows[0].get('business_order_id') else None
        p=wait_for(business,lambda r:r is not None,'Gift order conversion did not complete',180)
        o={'id':p['business_order_id'],'tid':tid,'oid':oid};b=order(o);assert b['inventory_status']=='RESERVED',b.get('stock_message');o['order_sn']=b['order_sn'];c['orders'][name]=o;save()
        g=gift(o);assert g['quantity']==2 and g['platform_oid']=='' and g['payment']==0
        checked('new '+name+' order reserves purchased and gift stock with separate identities')
    returning=c['orders']['return']
    if not returning.get('shipped'):
        b=order(returning)
        if b['review_status']!='APPROVED':assert api('/order/orders/business/approve-batch',{'orders':[{'id':b['id'],'control_version':b['control_version']}],'reason':'[赠品验收] 测试仓库出库'})[0]['success']
        b=order(returning)
        if b['allocation_status']!='FULL':assert api('/order/orders/business/allocate-batch',{'items':[{'id':b['id'],'control_version':b['control_version']}]})[0]['success']
        for sh in order(returning)['shipments']:
            def shipment():return api('/order/orders/shipments/'+str(sh['id']))
            s=shipment()
            if s['carrier_match_status']!='MATCHED':assert api('/order/orders/shipments/match-carrier',{'items':[{'id':s['id'],'control_version':s['control_version']}]})[0]['success']
            s=shipment()
            if s['wms_dispatch_state'] in ['NONE','FAILED']:assert api('/order/orders/shipments/dispatch',{'items':[{'id':s['id'],'control_version':s['control_version']}]})[0]['success']
            s=shipment()
            if s['wms_dispatch_state']=='PENDING':api('/order/orders/shipments/'+str(s['id'])+'/continue-dispatch',{'control_version':s['control_version']})
            s=wait_for(shipment,lambda r:r['wms_dispatch_state']=='ACCEPTED','TEST warehouse did not receive gift shipment',120)
            assert s['warehouse_target']['environment']=='TEST'
            if s['shipped_quantity']!=s['quantity']:
                r=subprocess.run([sys.executable,str(Path(__file__).with_name('simulate_qimen_delivery.py')),s['shipment_sn']],capture_output=True,text=True,encoding='utf-8',timeout=150)
                assert r.returncode==0,'TEST gift outbound failed: '+r.stdout[-350:]+r.stderr[-350:]
            s=wait_for(shipment,lambda r:r['shipped_quantity']==r['quantity'],'Gift stock posting incomplete',120)
            assert s['quantity']==7
        returning['shipped']=True;save();checked('five purchased goods and two gifts share normal allocation, XS and TEST WMS outbound')
    for index,name in enumerate(['pending','cancel','return'],1):
        o=c['orders'][name]
        if o.get('refund_id'):continue
        rid='97'+c['stamp']+str(index).zfill(2);status='WAIT_SELLER_AGREE' if name=='pending' else 'SUCCESS'
        p={'refund_id':rid,'tid':o['tid'],'oid':o['oid'],'seller_nick':c['seller'],'status':status,'refund_fee':'10.00','num':2,'has_good_return':name=='return','dispute_type':'REFUND_AND_RETURN' if name=='return' else 'REFUND','refund_phase':'aftersale' if name=='return' else 'onsale','refund_version':'1','reason':'[赠品验收] 退两件，剩三件只能保留一件赠品','modified':c['created']}
        payload=json.dumps({'refund_get_response':{'refund':p}},ensure_ascii=False)
        sql('INSERT INTO jdp_tb_refund(refund_id,tid,oid,status,seller_nick,created,modified,jdp_modified,jdp_response) VALUES ('+','.join(quote(v) for v in [rid,o['tid'],o['oid'],status,c['seller'],c['created'],c['created'],c['created'],payload])+')',SOURCE)
        api('/order/refunds/sync',{'channel_id':c['channel_id'],'mode':'IDS','refund_ids':[rid],'request_key':'GIFT_RF_'+c['stamp']+'_'+name})
        def business_refund():
            rows=api('/order/refunds/platform?number='+rid)['rows'];return rows[0] if rows and rows[0].get('business_refund_id') else None
        p=wait_for(business_refund,lambda r:r is not None,'Gift refund conversion incomplete',180);o['refund_id']=p['business_refund_id'];o['refund_number']=rid;save()
        b=refund(o);body={'control_version':b['control_version'],'platform_version':b['platform']['version'],'purpose':'CANCEL','goods_action':'RETURN' if name=='return' else 'CANCEL','reason':'[赠品验收] 已确认商品范围和赠品影响','evidence':'TEST-GIFT-'+c['stamp']}
        if name!='return':body.update(cancel_line_id=next(l['id'] for l in b['lines'] if not l.get('gift_plan_id')),cancel_quantity=2)
        api('/order/refunds/business/'+str(o['refund_id'])+'/review',body)
    p=gift(c['orders']['pending']);assert p['pending_assessment'] and p['eligible_quantity']==2 and p['projected_eligible_quantity']==1 and p['cancel_required']==0
    checked('pending refund pauses gifts without cancellation or stock release')
    o=c['orders']['cancel'];g=wait_for(lambda:gift(o),lambda g:g['cancelled_quantity']==1,'Gift cancellation after approved refund did not complete',120)
    assert g['cancelled_quantity']==1 and g['reserved_quantity']==1 and g['return_required']==0
    checked('confirmed refund cancels only the excess unshipped gift and releases its actual reservation')
    o=returning;g=gift(o);assert g['cancel_required']==0
    if not o.get('return_plan'):
        b=refund(o);source=g['sources'][0]
        o['return_plan']=api('/order/gifts/refunds/'+str(o['refund_id'])+'/return',{'control_version':b['control_version'],'gift_plan_id':report(o)['id'],'shipment_line_id':source['id'],'quantity':1,'store_code':c['store'],'return_address':'[赠品验收] 退回地址，无实际配送','return_contact':'TEST-GIFT-RETURN','reason':'[赠品验收] 已发两件，退两件正品后应退一件赠品','evidence':'TEST-GIFT-'+c['stamp'],'confirmed':True,'request_key':'GIFT_RETURN_'+c['stamp']})['id'];save()
    def plan():return next(p for p in api('/order/refunds/business/'+str(o['refund_id'])+'/goods')['plans'] if p['id']==o['return_plan'])
    def action(kind,doc=0,body=None):
        p=plan();b=refund(o)
        return api(f"/order/gifts/refunds/{o['refund_id']}/plans/{p['id']}/documents/{doc}/{kind}",{'control_version':b['control_version'],'plan_version':p['control_version'],**(body or {})})
    if not plan()['actual']:action('dispatch')
    p=plan();a=p['actual'];assert a['dispatch_state']=='ACCEPTED' and a['quantity']==1
    doc=a['documents'][0];o['return_sn']=doc['return_sn'];save()
    if not o.get('receipt_done'):
        before=count(c['gift_sku']);connection=next(v for v in api('/supplychain/wmsIntegration/connections') if v['id']==c['connection_id'])
        credential=next(v for v in json.loads((ROOT/'docker/local/wms-simulator-credentials.json').read_text('utf-8')) if v['app_key']==connection['app_key'] and v['customer_id']==connection['customer_id'])
        assert connection['environment']=='TEST'
        root=ET.Element('request');head=ET.SubElement(root,'returnOrder')
        for k,v in {'returnOrderCode':doc['return_sn'],'returnOrderId':doc['external_order'],'warehouseCode':a['external_warehouse'],'ownerCode':a['external_owner'],'outBizCode':'GIFT_RECEIPT_'+c['stamp'],'orderType':'THRK','confirmType':'0'}.items():ET.SubElement(head,k).text=str(v)
        line=ET.SubElement(ET.SubElement(root,'orderLines'),'orderLine')
        for k,v in {'itemCode':c['gift_sku'],'ownerCode':a['external_owner'],'actualQty':1,'inventoryType':'ZP','batchCode':'TEST-GIFT-RETURN-'+c['stamp']}.items():ET.SubElement(line,k).text=str(v)
        body=ET.tostring(root,encoding='unicode');params={'app_key':connection['app_key'],'customerId':connection['customer_id'],'method':'taobao.qimen.returnorder.confirm','v':'2.0','format':'xml','sign_method':'md5','timestamp':datetime.now().strftime('%Y-%m-%d %H:%M:%S')};params['sign']=sign(params,body,credential['secret'])
        for _ in range(2):
            req=urllib.request.Request(BASE+'/prod-api/supplychain/wmsCallback/qm/'+connection['callback_key']+'?'+urllib.parse.urlencode(params),data=body.encode(),headers={'Content-Type':'application/xml;charset=UTF-8'},method='POST')
            with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req,timeout=60) as response:reply=ET.fromstring(response.read())
            assert reply.findtext('flag')=='success',reply.findtext('message')
        action('posting',doc['id']);p=plan();assert p['posted_quantity']==1 and count(c['gift_sku'])==before+1
        assert refund(o)['funds_state']=='SUCCESS';o['receipt_done']=True;o['gift_stock_after_return']=count(c['gift_sku']);save()
    assert gift(o)['returned_quantity']==1 and gift(o)['return_required']==0
    checked('gift THK receipt and duplicate callback restore inventory once without changing platform funds')
    from playwright.sync_api import sync_playwright,expect
    with sync_playwright() as pw:
        browser=pw.chromium.launch(headless=True);ctx=browser.new_context(viewport={'width':1700,'height':1100});ctx.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=ctx.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
        page.goto(BASE+'/oms-order/gifts',wait_until='networkidle');expect(page.locator('.gift-management h2')).to_be_visible();page.get_by_role('button',name='新增策略组').click();expect(page.get_by_text('组内策略 · 从上到下执行',exact=True)).to_be_visible();page.screenshot(path=str(OUT/'rule-editor.png'),full_page=True,animations='disabled');page.locator('.el-dialog:visible').get_by_role('button',name='取消',exact=True).click()
        for name in ['pending','cancel','return']:
            page.goto(BASE+'/oms-order/gifts?orderId='+str(c['orders'][name]['id']),wait_until='networkidle');expect(page.get_by_role('heading',name='赠品与售后处理',exact=True)).to_be_visible();page.screenshot(path=str(OUT/(name+'.png')),full_page=True,animations='disabled')
        assert not errors,errors;browser.close()
    checked('fresh browser verifies rule editor and pending/cancel/returned gift guidance')
    (OUT/'acceptance.json').write_text(json.dumps(c,ensure_ascii=False,indent=2),encoding='utf-8')
    print('PASS gift acceptance; retained '+str(manifest),flush=True)


if __name__=='__main__':main()
