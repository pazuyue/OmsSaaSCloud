"""One isolated local acceptance: no shipment submission, WMS receipt, or platform send.
All warehouse stock is received through the normal API. Existing jobs stay untouched.
"""
import json,sys,time,statistics,hashlib,subprocess
from pathlib import Path
from datetime import datetime
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE
from test_multi_virtual_shipping import sql,quote,api as business_api,wait_for,stock
from test_platform_shipping_order import save
OUT=Path(__file__).resolve().parents[2]/'outputs/allocation-rules-v2'
MANIFEST=OUT/'case.json'

def api(path,credential,payload=None,post=False):
    if path=='/inventory/api/WmsInventory/addInventory?company_code=QM':
        case=json.loads(MANIFEST.read_text(encoding='utf8'));batch=payload['wmsInventoryBatch'];assert batch['skuSn']==case['sku'] and batch['companyCode']=='QM' and batch['storeCode'] in [w['code'] for w in case['warehouses']]
        # The receive endpoint is internal-only. Exercise it inside the local inventory container.
        result=subprocess.run(['wsl.exe','--','docker','exec','-i','oms-local-inventory','curl','-fsS','-H','from-source: inner','-H','Content-Type: application/json','--data-binary','@-','http://localhost:9303/api/WmsInventory/addInventory?company_code=QM'],input=json.dumps(payload).encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=30)
        assert result.returncode==0,'Local test receive API unavailable';reply=json.loads(result.stdout);assert reply['code']==200,reply.get('msg');return reply
    return business_api(path,credential,payload,post)

def prepare_case(case, credential, approve=True, before_conversion=None, order_quantity=10, require_reserved=True, template_code="VC0001"):
    goods_db, channel_db, order_db = 'qm_oms_saas_commodity', 'qm_oms_saas_channel', 'qm_oms_saas_order'
    if not sql('SELECT id FROM goods_sku_sn_info WHERE UPPER(company_code)=\'QM\' AND sku_sn=' + quote(case['sku']), goods_db):
        sample = api('/goods/info/list?pageNum=1&pageSize=1', credential, {}, post=True)['rows'][0]
        api('/goods/info', credential, {'skuSn': case['sku'], 'goodsSn': case['sku'], 'goodsName': '[验收测试] 分仓策略、上限与预览', 'categoryCode': sample['categoryCode'], 'colorCode': sample['colorCode'], 'sizeCode': sample['sizeCode'], 'marketPrice': 1, 'validity': 30})
    template = next(w for w in api('/supplychain/simulationStore/list?pageSize=100', credential)['rows'] if w['wmsSimulationCode'] == template_code)
    connection = next(c for c in api('/supplychain/wmsIntegration/connections', credential)['data'] if c['id'] == template['connectionId'])
    assert connection['environment'] == 'TEST' and connection['endpoint'] == 'http://wms-simulator:8080/qimen/gateway'
    case.update(owner_code=template['ownerCode'], real_store_code=template['realStoreCode'], connection_id=connection['id'])
    for index, warehouse in enumerate(case['warehouses'], 1):
        existing = sql('SELECT id FROM wms_simulation_store_info WHERE company_code=\'QM\' AND wms_simulation_code=' + quote(warehouse['code']), goods_db)
        if not existing:
            warehouse['id'] = api('/supplychain/simulationStore', credential, {'wmsSimulationCode': warehouse['code'], 'wmsSimulationName': '[验收测试] V' + str(index) + ' 多虚拟仓独立发货', 'ownerWarehouseId': template['ownerWarehouseId'], 'inboundMode': 2, 'outboundMode': 1, 'connectionId': connection['id'], 'externalWarehouse': template['externalWarehouse'], 'externalOwner': template['externalOwner'], 'status': 2})['data']
        else:
            warehouse['id'] = int(existing)
        detail = api('/supplychain/simulationStore/' + str(warehouse['id']), credential)['data']
        assert detail['realStoreCode'] == case['real_store_code'] and detail['ownerCode'] == case['owner_code'] and detail['connectionId'] == connection['id'] and detail['outboundMode'] == 1
        save(MANIFEST, case)
    if 'channel_id' not in case:
        existing = sql("SELECT channel_id FROM t_channel WHERE UPPER(company_code)='QM' AND channel_name=" + quote(case['channel_name']), channel_db)
        if not existing:
            api('/channel/channel', credential, {'channelName': case['channel_name'], 'channelType': 'TM', 'enabled': 1, 'toChannelEnabled': 1, 'syncEnabled': 0, 'mmodelType': 1})
            existing = sql("SELECT channel_id FROM t_channel WHERE UPPER(company_code)='QM' AND channel_name=" + quote(case['channel_name']), channel_db)
        case['channel_id'] = int(existing)
        save(MANIFEST, case)
    channel = case['channel_id']
    if not any(c['channel_id'] == channel for c in api('/order/orders/configs', credential)['data']):
        api('/order/orders/configs', credential, {'channel_id': channel, 'source_key': 'sysinfo113', 'seller_nick': case['seller'], 'enabled': 0, 'auto_convert': 0, 'review_mode': 'MANUAL', 'manual_match_review': 1, 'allowed_trade_types': ['fixed'], 'allow_public_stock': 1, 'interval_seconds': 30, 'initial_time': case['created']})
    if not case.get('configured'):
        config = api('/order/orders/allocation-config/' + str(channel), credential)['data']
        api('/order/orders/allocation-config', credential, {'channel_id': channel, 'revision': config['revision'], 'policy': {'enabled': 1, 'owner_code': case['owner_code'], 'warehouse_order': [w['code'] for w in case['warehouses']], 'province_priorities': {'北京': [case['warehouses'][0]['code']]}, 'allow_multi': 1, 'allow_partial': 1, 'allow_split_sku': 1}})
        carrier = next(c for c in api('/order/orders/carrier-configs', credential)['data'] if c['channel_id'] == channel)
        api('/order/orders/carrier-configs', credential, {'channel_id': channel, 'revision': carrier['revision'], 'policy': {'enabled': 1, 'carrier_order': ['SF'], 'routes': []}})
        config = api('/order/orders/platform-shipping-config/' + str(channel), credential)['data']
        api('/order/orders/platform-shipping-config/' + str(channel), credential, {'control_version': config['control_version'], 'mode': 'SIMULATION', 'carrier_mapping': {'SF': 'SF'}})
        case['configured'] = True
        save(MANIFEST, case)
    if not case.get('received'):
        for index, warehouse in enumerate(case['warehouses'], 1):
            api('/inventory/api/WmsInventory/addInventory?company_code=QM', credential, {'relationSn': 'TEST-MULTI-IN-' + case['case_id'] + '-V' + str(index), 'wmsInventoryBatch': {'companyCode': 'QM', 'storeCode': warehouse['code'], 'skuSn': case['sku'], 'batchCode': 'TEST-MULTI-BATCH-' + case['case_id'] + '-V' + str(index), 'zpActualNumber': warehouse['quantity'], 'zpAvailableNumber': warehouse['quantity'], 'zpLockNumber': 0, 'cpActualNumber': 0, 'cpAvailableNumber': 0, 'cpLockNumber': 0}})
        # Initial quota/scope metadata for the isolated shop/SKU only. Physical and
        # company stock quantities are created exclusively by the receive API.
        scope = json.dumps([w['code'] for w in case['warehouses']])
        sql(f"INSERT IGNORE INTO oms_channel_inventory(company_code,channel_id,sku_sn,quota_stock,stock_scope_json) VALUES ('QM',{channel},{quote(case['sku'])},10,{quote(scope)})", 'qm_oms_saas_inventory')
        case['received'] = True
        case['initial_stock'] = stock(case)
        assert case['initial_stock']['company'][0]['total'] == 10
        save(MANIFEST, case)
    if before_conversion is not None:before_conversion(case,credential)
    platform = sql(f"SELECT id FROM oms_platform_order WHERE company_code='QM' AND channel_id={channel} AND platform_tid={quote(case['platform_tid'])}", order_db)
    if not platform:
        trade = {'tid': case['platform_tid'], 'status': 'WAIT_SELLER_SEND_GOODS', 'type': 'fixed', 'seller_nick': case['seller'], 'created': case['created'], 'modified': case['created'], 'payment': '10.00', 'receiver_name': '多虚拟仓验收收件人', 'receiver_mobile': 'TEST-MASKED', 'receiver_state': '北京市', 'receiver_city': '北京市', 'receiver_district': '验收测试区', 'receiver_address': '验收测试地址，无实际配送', 'orders': {'order': [{'oid': case['platform_oid'], 'outer_sku_id': case['sku'], 'num': order_quantity, 'payment': '10.00', 'title': '[验收测试] 分仓策略、上限与预览', 'sku_properties_name': '验收:同一SKU跨虚拟仓'}]}}
        payload = json.dumps({'trade_fullinfo_get_response': {'trade': trade}}, ensure_ascii=False)
        digest = hashlib.sha256(payload.encode()).hexdigest()
        sql(f"INSERT INTO oms_platform_order(company_code,channel_id,platform_tid,platform_status,trade_type,seller_nick,platform_created,platform_modified,source_modified,payload_hash,raw_payload,conversion_status) VALUES ('QM',{channel},{quote(case['platform_tid'])},'WAIT_SELLER_SEND_GOODS','fixed',{quote(case['seller'])},{quote(case['created'])},{quote(case['created'])},{quote(case['created'])},{quote(digest)},{quote(payload)},'WAITING')", order_db)
        platform = sql(f"SELECT id FROM oms_platform_order WHERE company_code='QM' AND channel_id={channel} AND platform_tid={quote(case['platform_tid'])}", order_db)
    case['platform_id'] = int(platform)
    save(MANIFEST, case)
    platform_path = '/order/orders/platform/' + str(case['platform_id'])
    current = api(platform_path, credential)['data']
    if not current.get('business_order_id') and current['conversion_status'] in ['WAITING', 'EXCEPTION']:
        api(platform_path + '/retry', credential, post=True)
    current = wait_for(lambda: api(platform_path, credential)['data'], lambda p: p.get('business_order_id') is not None, 'Conversion did not complete')
    case['order_id'] = current['business_order_id']
    order_path = '/order/orders/business/' + str(case['order_id'])
    business = wait_for(lambda: api(order_path, credential)['data'], lambda b: not require_reserved or b['inventory_status'] == 'RESERVED' or case.get('completed'), 'Logical reservation did not complete')
    case['order_sn'] = business['order_sn']
    if approve and business['review_status'] != 'APPROVED':
        response = api('/order/orders/business/approve-batch', credential, {'orders': [{'id': business['id'], 'control_version': business['control_version']}], 'reason': '分仓规则迭代验收'})['data']
        assert response[0]['success'], response
        business = api(order_path, credential)['data']
    save(MANIFEST, case)
    return business


def main():
    assert sys.argv[1:] in (['--seed'],['--verify'])
    OUT.mkdir(parents=True,exist_ok=True)
    if MANIFEST.exists():case=json.loads(MANIFEST.read_text(encoding='utf8'))
    else:
        stamp=datetime.now().strftime('%Y%m%d%H%M%S');tid='995'+stamp
        case={'case_id':stamp,'created':datetime.now().strftime('%Y-%m-%d %H:%M:%S'),'sku':'ACCEPT_ALLOC_'+stamp,'channel_name':'[验收] 分仓策略与预览 '+stamp,'seller':'ACCEPT_ALLOC_'+stamp,'platform_tid':tid,'platform_oid':tid+'01','warehouses':[{'code':'ALLOC_V1_'+stamp,'quantity':6,'package_count':0},{'code':'ALLOC_V2_'+stamp,'quantity':4,'package_count':0}]}
        save(MANIFEST,case)
    assert case['sku'].startswith('ACCEPT_ALLOC_') and case['platform_tid'].startswith('995')
    credential=login()
    try:
        prepare_case(case,credential);path='/order/orders/business/'+str(case['order_id'])
        def order():return api(path,credential)['data']
        def policy(**changes):
            config=api('/order/orders/allocation-config/'+str(case['channel_id']),credential)['data'];p={'enabled':1,'owner_code':case['owner_code'],'warehouse_order':[w['code'] for w in case['warehouses']],'province_priorities':{'北京':[case['warehouses'][0]['code']]},'allow_multi':1,'allow_partial':1,'allow_split_sku':1,'strategy':'FEWEST_SHIPMENTS','max_virtual_warehouses':0,'max_shipments':0,'min_first_allocation_percent':0,**changes}
            api('/order/orders/allocation-config',credential,{'channel_id':case['channel_id'],'revision':config['revision'],'policy':p});return p
        def preview():
            current=order();started=time.perf_counter();r=api('/order/orders/business/allocation-preview',credential,{'items':[{'id':current['id'],'control_version':current['control_version']}]})['data'][0];return r,round((time.perf_counter()-started)*1000,2)
        def confirm(report):return api('/order/orders/business/allocate-batch',credential,{'items':[{k:report[k] for k in ['id','control_version','policy_fingerprint','plan_fingerprint']}]})['data'][0]
        if sys.argv[1:]==['--seed']:
            print(json.dumps({'order_id':case['order_id'],'order_sn':case['order_sn'],'stock_received':10,'allocated':order()['allocation_quantity']},ensure_ascii=True));return
        if not case.get('scenarios_verified'):
            before=stock(case)
            policy(max_virtual_warehouses=1,allow_partial=0);limited,_=preview();assert not limited['can_allocate']
            policy(max_shipments=1,allow_partial=0);limited,_=preview();assert not limited['can_allocate']
            policy(max_virtual_warehouses=1,min_first_allocation_percent=70);limited,_=preview();assert not limited['can_allocate'] and '比例' in limited['message']
            policy(max_virtual_warehouses=1,min_first_allocation_percent=60);partial,_=preview();assert partial['can_allocate'] and partial['planned_quantity']==6 and partial['new_shipment_count']==1
            policy();old,_=preview();assert old['can_allocate'] and old['planned_quantity']==10
            policy();assert not confirm(old)['success'];assert stock(case)==before,'Preview or stale rules changed stock'
            old,_=preview();case['before_restock_preview']={k:old[k] for k in ['id','control_version','policy_fingerprint','plan_fingerprint']};case['scenarios_verified']=True;save(MANIFEST,case)
        if not case.get('extra_received'):
            w=case['warehouses'][1];api('/inventory/api/WmsInventory/addInventory?company_code=QM',credential,{'relationSn':'TEST-ALLOC-EXTRA-'+case['case_id'],'wmsInventoryBatch':{'companyCode':'QM','storeCode':w['code'],'skuSn':case['sku'],'batchCode':'TEST-ALLOC-EXTRA-'+case['case_id'],'zpActualNumber':6,'zpAvailableNumber':6,'zpLockNumber':0,'cpActualNumber':0,'cpAvailableNumber':0,'cpLockNumber':0}});case['extra_received']=True;save(MANIFEST,case)
        if not case.get('plan_change_verified'):
            before=stock(case);assert not confirm(case['before_restock_preview'])['success'];assert stock(case)==before,'Stale plan added stock occupation';case['plan_change_verified']=True;save(MANIFEST,case)
        if not case.get('completed'):
            before=stock(case);policy(strategy='FEWEST_SHIPMENTS',max_virtual_warehouses=2,max_shipments=2);whole,_=preview();assert whole['can_allocate'] and whole['new_shipment_count']==1 and {line['store_code'] for line in whole['lines']}=={case['warehouses'][1]['code']}
            policy(strategy='REGION_FIRST',max_virtual_warehouses=2,max_shipments=2);regional,_=preview();assert regional['can_allocate'] and regional['new_shipment_count']==2
            quantities={}
            for line in regional['lines']:quantities[line['store_code']]=quantities.get(line['store_code'],0)+line['quantity']
            assert quantities=={case['warehouses'][0]['code']:6,case['warehouses'][1]['code']:4};assert stock(case)==before
            timings=[]
            for _ in range(20):report,elapsed=preview();assert report['can_allocate'];timings.append(elapsed)
            case['performance']={'requests':20,'p50_ms':statistics.median(timings),'p95_ms':sorted(timings)[18],'max_ms':max(timings),'stock_unchanged_after_previews':stock(case)==before}
            with sync_playwright() as p:
                browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1550,'height':1150});context.add_cookies([{'name':'Admin-Token','value':credential,'url':BASE}]);page=context.new_page();page.set_default_timeout(30000);errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
                page.goto(BASE+'/oms-order/settings?tab=allocation&channelId='+str(case['channel_id']),wait_until='networkidle');settings=page.get_by_role('dialog',name='店铺分仓规则',exact=True);expect(settings.get_by_text('分仓策略',exact=True)).to_be_visible();expect(settings.get_by_text('最多发货单数',exact=True)).to_be_visible();page.screenshot(path=str(OUT/'settings.png'),full_page=True);settings.get_by_role('button',name='取消',exact=True).click()
                page.goto(BASE+'/oms-order/business?view=business&orderId='+str(case['order_id']),wait_until='networkidle');drawer=page.locator('.el-drawer:visible');drawer.get_by_role('tab',name='分仓明细',exact=True).click();drawer.get_by_role('button',name='预览分仓',exact=True).click();dialog=page.get_by_role('dialog',name='订单分仓预览',exact=True);expect(dialog.get_by_text('可确认',exact=True)).to_be_visible();expect(dialog.get_by_text('优先省份配置仓',exact=True)).to_be_visible();page.screenshot(path=str(OUT/'preview.png'),full_page=True)
                assert stock(case)==before,'Opening UI preview changed stock';dialog.get_by_role('button',name='确认分仓',exact=False).click();expect(dialog.get_by_text('已处理',exact=True)).to_be_visible(timeout=60000);page.screenshot(path=str(OUT/'result.png'),full_page=True);dialog.get_by_role('button',name='关闭',exact=True).click();drawer.get_by_role('tab',name='分仓明细',exact=True).click();expect(drawer.get_by_text('各仓发货与包裹',exact=True)).to_be_visible();page.screenshot(path=str(OUT/'allocation-detail.png'),full_page=True);assert not errors,errors;browser.close()
            current=order();assert current['allocation_quantity']==10 and current['allocation_status']=='FULL' and len(current['shipments'])==2
            assert all(s['wms_dispatch_state']=='NONE' and s['shipped_quantity']==s['package_count']==0 for s in current['shipments']);assert current['allocation_rule']['strategy']=='REGION_FIRST'
            snapshot=stock(case);assert sum(w['locked'] for w in snapshot['warehouse'])==10 and sum(w['actual'] for w in snapshot['warehouse'])==16 and sum(w['available'] for w in snapshot['warehouse'])==6
            assert sum(w['quantity'] for w in snapshot['allocation'])==10
            assert not confirm(regional)['success'];assert stock(case)==snapshot,'Duplicate submit changed stock'
            case['shipments']=[{k:s[k] for k in ['id','shipment_sn','quantity','store_code']} for s in current['shipments']];case['completed']=True;case['business_url']=BASE+'/oms-order/business?view=business&orderId='+str(case['order_id']);case['stock_after']=snapshot;save(MANIFEST,case)
        from verify_tenant_routing_cleanup import snapshot
        assert snapshot()==json.loads((OUT/'before.json').read_text(encoding='utf8')),'Retained stock, review decisions or Quartz settings changed'
        verification={'order_sn':case['order_sn'],'order_id':case['order_id'],'business_url':case['business_url'],'strategy':'REGION_FIRST','virtual_warehouses':2,'shipments':case['shipments'],'allocated_quantity':10,'actual_stock':16,'warehouse_occupied':10,'remaining_warehouse_available':6,'warehouse_submission_performed':False,'real_platform_send_performed':False,'preview_no_mutation':True,'duplicate_submit_stock_unchanged':True,'stale_rules_rejected':True,'stale_plan_rejected':True,'split_caps_verified':True,'first_partial_threshold_verified':True,'three_existing_orders_and_jobs_unchanged':True,'performance':case['performance']}
        (OUT/'verification.json').write_text(json.dumps(verification,ensure_ascii=False,indent=2),encoding='utf8');print(json.dumps(verification,ensure_ascii=True))
    finally:request('/auth/logout',token=credential,method='DELETE')
if __name__=='__main__':main()
