"""Retained TEST carrier acceptance. Never changes existing business documents, stock, jobs or queue settings."""
import json,time,urllib.parse,urllib.request,xml.etree.ElementTree as ET
from datetime import datetime,timedelta,timezone
from pathlib import Path
from verify_goods_workspace_ui import login,request,BASE
from test_multi_virtual_shipping import sql,quote
from wms_qimen_simulator import sign

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/shared-carriers';API='/order/sharedCarriers';SC='/supplychain';PO=SC+'/purchaseWorkspace';RET=SC+'/purchaseReturns'


def main():
    OUT.mkdir(exist_ok=True);path=OUT/'manifest.json';state=json.loads(path.read_text('utf-8')) if path.exists() else dict(stamp=datetime.now().strftime('%Y%m%d%H%M%S'),commands={},checks=[],ids={})
    stamp=state['stamp'];credential=login()
    def save():path.write_text(json.dumps(state,ensure_ascii=False,indent=2),'utf-8')
    def api(p,body=None):return request(p,payload=body,token=credential)
    def once(key,p,body):
        if key in state['commands']:return state['commands'][key]['response']
        result=api(p,body);state['commands'][key]=dict(path=p,response=result);save();return result
    def check(key,scenario,evidence):
        row=dict(key=key,scenario=scenario,result='PASS',evidence=evidence,time=datetime.now().isoformat());state['checks']=[r for r in state['checks'] if r['key']!=key]+[row];save();print('PASS',key,scenario,flush=True)
    def wait(fn,valid,label):
        for _ in range(90):
            result=fn()
            if valid(result):return result
            time.sleep(1)
        raise AssertionError(label+': '+str(result)[:500])
    def fails(p,body=None,contains=''):
        try:api(p,body)
        except AssertionError as exc:
            assert contains in str(exc),(contains,str(exc));return str(exc)[:500]
        raise AssertionError('Expected failure: '+p)
    def scope(system,purpose,**extra):return dict(system_type=system,provider='TMALL' if system=='CHANNEL' else 'QIMEN',purpose=purpose,connection_id='',shop_id='',owner_code='',real_store_code='',**extra)
    def directory(key,context,code,name=None):return once(key,API+'/directory/save',dict(context,revision=0,enabled=1,external_code=code,external_name=name or '[公共快递TEST] '+code,evidence='TEST 本地目录验收，非官方真实编码'))['data']
    def mapping(key,context,standard,code,direction='BOTH',default=1):
        d=directory(key+'_DIRECTORY',context,code)
        return once(key,API+'/mapping/save',dict(context,revision=0,enabled=1,directory_id=d['id'],standard_id=standard,direction=direction,is_default=default,product_code='',evidence='TEST 人工确认目录与标准身份'))['data']
    def preview(source,target):return api(API+'/preview',dict(source=source,target=target))['data']
    def stock():
        row=api('/inventory/productInventory?'+urllib.parse.urlencode(dict(skuSn=state['sku'])))['rows'][0];assert row['checkStatus']=='CONSISTENT',row
        return {k:row[k] for k in ['skuSn','totalStock','availableStock','allocatedStock','reservedStock','frozenStock'] if k in row}
    for code in ['A','B']:
        if 'standard_'+code not in state['ids']:
            result=once('STANDARD_'+code,API+'/standard/save',dict(revision=0,standard_code='TEST_SC_'+code+'_'+stamp,standard_name='[公共快递验收] 承运商'+code+' '+stamp,aliases=['测试别名'+code+'_'+stamp],category='EXPRESS',enabled=1))['data'];state['ids']['standard_'+code]=result['id'];save()
    a=state['ids']['standard_A'];b=state['ids']['standard_B']
    search=api(API+'/standards/search?'+urllib.parse.urlencode(dict(keyword='测试别名A_'+stamp)))['data'];assert any(r['id']==a for r in search['rows'])
    check('A01','标准快递无需仓库能力即可独立启用，别名可搜索；重复编码被拒绝',dict(standard_a=a,standard_b=b,duplicate=fails(API+'/standard/save',dict(revision=0,standard_code='TEST_SC_A_'+stamp,standard_name='重复测试',aliases=[],category='EXPRESS',enabled=1),'已存在')))
    # Dedicated local TEST warehouse and source inventory. Credentials are never written to the manifest.
    creds=json.loads((ROOT/'docker/local/wms-simulator-credentials.json').read_text('utf-8'))[0]
    for key,p,body in [('owner',SC+'/owner',dict(ownerCode='SCO_'+stamp,ownerName='[公共快递TEST] '+stamp,isEnable=2)),('warehouse',SC+'/realStore',dict(realStoreCode='SCW_'+stamp,wmsName='[公共快递TEST] '+stamp,wmsType=1,status=2))]:
        if key not in state['ids']:state['ids'][key]=api(p,body)['data'];save()
    if 'relation' not in state['ids']:state['ids']['relation']=api(SC+'/ownerWarehouse',dict(ownerId=state['ids']['owner'],realStoreId=state['ids']['warehouse'],status=2))['data'];save()
    for key in ['connection','second_connection']:
        if key not in state['ids']:
            state['ids'][key]=api(SC+'/wmsIntegration/connections',dict(name='[公共快递TEST] '+key+' '+stamp,provider='QIMEN',api_version='2.0',environment='TEST',endpoint='http://wms-simulator:8080/qimen/gateway',app_key=creds['app_key'],customer_id=creds['customer_id'],secret=creds['secret'],enabled=1))['data'];save()
    store='SCV_'+stamp;state['store']=store;state['sku']='SCARRIER_'+stamp;save()
    once('STORE',SC+'/simulationStore',dict(wmsSimulationCode=store,wmsSimulationName='[公共快递TEST] '+stamp,ownerWarehouseId=state['ids']['relation'],inboundMode=1,outboundMode=1,status=2,connectionId=state['ids']['connection'],externalWarehouse='SC_EXT_'+stamp,externalOwner='SC_OWNER_'+stamp))
    w=scope('WMS','WMS_OUTBOUND_CREATE');w.update(connection_id=str(state['ids']['connection']),owner_code='SCO_'+stamp,real_store_code='SCW_'+stamp)
    mapping('PLAN',w,a,'TEST-SC-PLAN-'+stamp,'OUT')
    for flow in ['OUTBOUND','INBOUND']:
        once('CAP_'+flow,API+'/capability/save',dict(revision=0,connection_id=w['connection_id'],owner_code=w['owner_code'],real_store_code=w['real_store_code'],standard_id=a,flow=flow,product_code='',provinces=['浙江'] if flow=='OUTBOUND' else [],excluded_provinces=[],enabled=1,evidence='TEST 仓库收发能力确认'))
    actual=dict(w,purpose='WMS_OUTBOUND_ACTUAL');mapping('ACTUAL_B',actual,b,'TEST-SC-B-001','IN',0)
    # Exact codes and connection scope are verified with read-only conversion previews.
    alias=scope('CHANNEL','CHANNEL_RETURN_IDENTIFY');mapping('TM_ALIAS_IDENTIFY',alias,a,'001/sF.a','IN',0)
    result=preview(dict(alias,external_code='001/sF.a'),dict(w,province='浙江'));assert result['source']['standard_id']==a and result['target']['external_code']=='TEST-SC-PLAN-'+stamp
    wrong=preview(dict(alias,external_code='1/sF.a'),dict(w,province='浙江'));assert wrong['source']['state']=='MISSING'
    check('A04-A07','接口用途、编码大小写与前导零分别识别，未做名称或数值猜测',dict(exact=result,missing=wrong))
    second=dict(actual,connection_id=str(state['ids']['second_connection']),owner_code='',real_store_code='');mapping('SECOND_ACTUAL',second,b,'TEST-SC-UNKNOWN-001','IN',0)
    result=preview(dict(second,external_code='TEST-SC-UNKNOWN-001'),dict(w,province='浙江'));assert result['source']['standard_id']==b
    check('A05','同一原始编码可在不同WMS连接映射为不同标准；第二连接独立识别为B',result)
    inbound=dict(w,purpose='WMS_RETURN_CREATE');mapping('INBOUND_A',inbound,a,'TEST-SC-IN-001','OUT')
    channel_return=scope('CHANNEL','CHANNEL_RETURN_IDENTIFY');mapping('TM_RETURN_IDENTIFY',channel_return,a,'TEST-TM-RETURN-001','IN',0)
    result=preview(dict(channel_return,external_code='TEST-TM-RETURN-001'),dict(inbound,province='海南'));assert result['target']['available'] is True
    out_result=preview(dict(channel_return,external_code='TEST-TM-RETURN-001'),dict(w,province='海南'));assert out_result['target']['state']=='CAPABILITY_UNSUPPORTED'
    check('A08-A09','同仓发货只配送浙江，海南发货不可用；海南寄回按收货能力通过',dict(inbound=result,outbound=out_result))
    # Real local procurement receipt: 10 in, 5 out in two physical packages sharing one waybill.
    options=api('/goods/info/options')['data']
    once('PRODUCT','/goods/info',dict(skuSn=state['sku'],goodsSn=state['sku'],goodsName='[公共快递TEST] 原始物流与库存独立验收',marketPrice=10,validity=30,categoryCode=next(r['id'] for r in options['category'] if r['level']==3),colorCode=options['color'][0]['id'],sizeCode=options['size'][0]['id']))
    if 'supplier' not in state['ids']:state['ids']['supplier']=api(PO+'/supplier',dict(supplierName='[公共快递TEST] '+stamp,companyName='[公共快递TEST]供应商_'+stamp,status=2))['data'];save()
    supplier=api(PO+'/supplier/'+str(state['ids']['supplier']))['data']['supplierSn']
    purchase=once('PURCHASE',PO+'/purchase',dict(poName='[公共快递TEST] '+stamp,supplierSn=supplier,wmsSimulationCode=store,lines=[dict(skuSn=state['sku'],quantity=10,purchasePrice=2.5)]))['data'];state['ids']['purchase']=purchase;save()
    po=api(PO+'/purchase/'+str(purchase))['data'];state['po_sn']=po['poSn'];save()
    if po['poState']==1:once('PO_APPROVE',PO+'/purchase/'+str(purchase)+'/approve',{})
    receipt=once('RECEIPT',PO+'/purchase/'+str(purchase)+'/receipt',dict(lines=[dict(lineId=po['lines'][0]['id'],quantity=10)]))['data']
    receipt_detail=api(PO+'/receipt/'+str(receipt))['data'];ticket=receipt_detail['tickets'][0]['id'] if receipt_detail['tickets'] else once('RECEIPT_APPROVE',PO+'/receipt/'+str(receipt)+'/approve',{})['data'];state['ids']['ticket']=ticket;save()
    task=wait(lambda:api(SC+'/wmsIntegration/tickets/'+str(ticket))['data'],lambda r:r['dispatch_state']=='ACCEPTED','采购入库下发')
    connection=next(r for r in api(SC+'/wmsIntegration/connections')['data'] if r['id']==state['ids']['connection'])
    def tags(parent,values):
        for k,v in values.items():ET.SubElement(parent,k).text=str(v)
    def wire(key,method,root):
        body=ET.tostring(root,encoding='unicode');params=dict(method='taobao.qimen.'+method,timestamp=datetime.now(timezone(timedelta(hours=8))).strftime('%Y-%m-%d %H:%M:%S'),format='xml',app_key=creds['app_key'],customerId=creds['customer_id'],v='2.0',sign_method='md5');params['sign']=sign(params,body,creds['secret']);url=BASE+'/prod-api'+SC+'/wmsCallback/qm/'+connection['callback_key']+'?'+urllib.parse.urlencode(params)
        reply=ET.fromstring(urllib.request.urlopen(urllib.request.Request(url,data=body.encode('utf-8'),headers={'Content-Type':'application/xml'},method='POST'),timeout=40).read());assert reply.findtext('flag')=='success',reply.findtext('message');state['commands'][key]=dict(message=reply.findtext('message'));save()
    if 'INBOUND_CALLBACK' not in state['commands']:
        root=ET.Element('request');tags(ET.SubElement(root,'entryOrder'),dict(entryOrderCode=task['ticket_sn'],entryOrderId=task['external_order'],warehouseCode=task['external_warehouse'],ownerCode=task['external_owner'],status='FULFILLED',confirmType=0,outBizCode='SC_IN_'+stamp,totalOrderLines=1));lines=ET.SubElement(root,'orderLines');tags(ET.SubElement(lines,'orderLine'),dict(itemCode=state['sku'],ownerCode=task['external_owner'],batchCode='SC_BATCH_'+stamp,actualQty=10,inventoryType='ZP'));wire('INBOUND_CALLBACK','entryorder.confirm',root)
    wait(lambda:api(PO+'/ticket/'+str(ticket))['data'],lambda r:r['numberPosted']==10,'采购实收入账')
    if 'stock_received' not in state:state['stock_received']=stock();save()
    assert state['stock_received']['totalStock']==10
    sources=api(RET+'/sources?purchaseId='+str(purchase))['data']['rows'];source=next(r for r in sources if r['inventory_type']=='ZP')
    returned=once('RETURN',RET,dict(request_key='SCARRIER_'+stamp,purchase_id=purchase,standard_id=a,receiver=dict(name='[TEST]供应商',mobile='13000000000',province='浙江省',city='杭州市',area='西湖区',detailAddress='本地TEST验收地址'),reason='[公共快递TEST] '+stamp,lines=[dict(source_key=source['source_key'],inventory_type='ZP',quantity=5)]))['data'];state['ids']['return']=returned;save()
    def doc():return api(RET+'/'+str(returned))['data']
    d=doc();state['return_sn']=d['return_sn'];save()
    if d['state']=='DRAFT':once('RETURN_APPROVE',RET+'/'+str(returned)+'/approve',dict(control_version=d['control_version']))
    d=wait(doc,lambda r:r['executions'] and r['executions'][0]['dispatch_state']=='ACCEPTED','采购退仓WMS受理');execution=d['executions'][0]
    if 'stock_held' not in state:state['stock_held']=stock();save()
    if 'OUTBOUND_CALLBACK' not in state['commands']:
        assert state['stock_held']['totalStock']==10 and state['stock_held']['allocatedStock']==5
        root=ET.Element('request');tags(ET.SubElement(root,'deliveryOrder'),dict(deliveryOrderCode=execution['execution_sn'],deliveryOrderId=execution['external_order'],warehouseCode='SC_EXT_'+stamp,ownerCode='SC_OWNER_'+stamp,status='DELIVERED',orderType='CGTH',confirmType=0,outBizCode='SC_OUT_'+stamp,totalOrderLines=1));lines=ET.SubElement(root,'orderLines');line=execution['lines'][0];tags(ET.SubElement(lines,'orderLine'),dict(orderLineNo=line['id'],itemCode=state['sku'],ownerCode='SC_OWNER_'+stamp,batchCode='SC_BATCH_'+stamp,inventoryType='ZP',actualQty=5));packages=ET.SubElement(root,'packages')
        for code,quantity,carrier in [('TEST-P1',2,'TEST-SC-UNKNOWN-001'),('TEST-P2',3,'TEST-SC-B-001')]:
            package=ET.SubElement(packages,'package');tags(package,dict(packageCode=code,logisticsCode=carrier,logisticsName='[TEST] 原始承运商 '+carrier,expressCode='TEST-SC-WAYBILL-'+stamp));items=ET.SubElement(package,'items');tags(ET.SubElement(items,'item'),dict(itemCode=state['sku'],quantity=quantity,orderLineNo=line['id'],batchCode='SC_BATCH_'+stamp,inventoryType='ZP'))
        wire('OUTBOUND_CALLBACK','stockout.confirm',root);wire('OUTBOUND_CALLBACK_DUPLICATE','stockout.confirm',root)
    d=wait(doc,lambda r:r['posted_quantity']==5,'实际出库入账');current=stock();assert current['totalStock']==5 and current['availableStock']==5 and current['allocatedStock']==0
    state['stock_posted']=current;save();assert len(d['parcels'])==2
    check('A10-A11-A14-A15','真实本地采购10件、退仓5件；两个包裹共用一个运单，未知原始快递不阻断实出；重复回传未重复扣库存',dict(return_sn=state['return_sn'],purchase=state['po_sn'],sku=state['sku'],stock_received=state['stock_received'],stock_held=state['stock_held'],stock_posted=current,parcels=[{k:p.get(k) for k in ['id','package_code','carrier_code','waybill']} for p in d['parcels']]))
    def todo():
        rows=api(API+'/todo/list?'+urllib.parse.urlencode(dict(business_type='PURCHASE_RETURN',business_id=returned,pageSize=100)))['data']['rows']
        return next((r for r in rows if r['fact_id']=='PARCEL_'+str(next(p['id'] for p in doc()['parcels'] if p['package_code']=='TEST-P1'))),{})
    missing=wait(todo,lambda r:bool(r),'未知原始物流映射待办')
    if 'before_repair' not in state:
        state['before_repair']=dict(stock=stock(),events=sql('SELECT id,message_id,quantity,state FROM purchase_return_event WHERE company_code=\'QM\' AND return_id='+str(returned)+' ORDER BY id','qm_oms_saas_commodity'),execution=sql('SELECT id,quantity,accepted_quantity,posted_quantity,dispatch_state FROM purchase_return_execution WHERE company_code=\'QM\' AND return_id='+str(returned)+' ORDER BY id','qm_oms_saas_commodity'));save()
    mapping('REPAIR_ACTUAL_A',actual,a,'TEST-SC-UNKNOWN-001','IN',0)
    current_todo=todo()
    if current_todo['state']!='COMPLETED':
        revalidated=api(API+'/todo/'+str(missing['id'])+'/revalidate',dict(revision=current_todo['revision']))['data'];assert revalidated['available'] is True
        current_todo=todo();assert current_todo['state']=='READY'
        retry=api(API+'/todo/'+str(missing['id'])+'/retry',dict(revision=current_todo['revision']))['data'];assert retry['available'] is True,retry
    d=doc();p1=next(p for p in d['parcels'] if p['package_code']=='TEST-P1');p2=next(p for p in d['parcels'] if p['package_code']=='TEST-P2')
    wait(doc,lambda r:all(p['carrier_resolution_state']=='RESOLVED' for p in r['parcels']),'包裹标准身份识别')
    d=doc();assert next(p for p in d['parcels'] if p['package_code']=='TEST-P1')['standard_id']==a
    assert next(p for p in d['parcels'] if p['package_code']=='TEST-P2')['standard_id']==b
    assert p1['carrier_code']=='TEST-SC-UNKNOWN-001' and p2['carrier_code']=='TEST-SC-B-001'
    assert stock()==state['before_repair']['stock']
    assert sql('SELECT id,message_id,quantity,state FROM purchase_return_event WHERE company_code=\'QM\' AND return_id='+str(returned)+' ORDER BY id','qm_oms_saas_commodity')==state['before_repair']['events']
    assert sql('SELECT id,quantity,accepted_quantity,posted_quantity,dispatch_state FROM purchase_return_execution WHERE company_code=\'QM\' AND return_id='+str(returned)+' ORDER BY id','qm_oms_saas_commodity')==state['before_repair']['execution']
    assert todo()['state']=='COMPLETED'
    overwrite=fails(RET+'/'+str(returned)+'/parcels/supplement',dict(request_key='SC_OVERWRITE_'+stamp,control_version=d['control_version'],execution_id=p1['execution_id'],package_code=p1['package_code'],parcel_version=p1['control_version'],carrier_code='TEST-MUST-NOT-REPLACE',evidence='[TEST] 验证已存在的原始物流不能覆盖'),'原始物流事实不能覆盖')
    assert next(p for p in doc()['parcels'] if p['package_code']=='TEST-P1')['carrier_code']=='TEST-SC-UNKNOWN-001'
    check('A12-A16','补齐原连接快递映射后重新校验，仅修复两个包裹的独立标准身份；原始编码不可覆盖，实际出库事件、仓库执行和库存均未重放',dict(todo=todo(),stock=stock(),parcels=d['parcels'],overwrite_rejected=overwrite))
    broad=dict(actual,owner_code='',real_store_code='');precise=dict(actual,real_store_code='');mapping('SHADOW_BROAD',broad,a,'TEST-SC-SHADOW-001','IN',0)
    shadow=mapping('SHADOW_PRECISE',precise,b,'TEST-SC-SHADOW-001','IN',0)
    if 'SHADOW_DISABLE' not in state['commands']:once('SHADOW_DISABLE',API+'/mapping/save',dict(shadow,enabled=0,disabled_reason='TEST 精确范围停用，禁止回退宽范围'))
    shadowed=preview(dict(actual,external_code='TEST-SC-SHADOW-001'),dict(inbound));assert shadowed['source']['state']=='DISABLED'
    check('A02-A03-A06','货主精确对照停用时明确阻断；不能回落到连接通用对照',shadowed)
    import_scope=dict(second,purpose='WMS_RETURN_ACTUAL',evidence='TEST 正式目录导入验收')
    bad=api(API+'/directory/import-preview',dict(import_scope,rows=[dict(external_code=1,external_name='数字编码不得转换为文本',enabled=1)]))['data'];assert bad['errors']==1
    rejected=fails(API+'/directory/import-confirm',dict(request_key=bad['request_key']),'错误')
    good=once('IMPORT_PREVIEW',API+'/directory/import-preview',dict(import_scope,rows=[dict(external_code='001/sF.a',external_name='[TEST] 原值目录',enabled=1)]))['data']
    imported=once('IMPORT_CONFIRM',API+'/directory/import-confirm',dict(request_key=good['request_key']))['data']
    check('A19-A20','目录先预览再确认；数字编码行被拒绝，文本编码保留前导零、大小写及标点；操作留痕',dict(error_preview=bad,rejected=rejected,confirmed=imported))
    mappings_before=api(API+'/mapping/list?'+urllib.parse.urlencode(dict(system_type='WMS',connection_id=w['connection_id'],pageSize=100)))['data']
    synced=once('SYNC_UNSUPPORTED',API+'/directory/sync',scope('CHANNEL','CHANNEL_SHIPPING'))['data'];assert synced['state']=='UNSUPPORTED'
    queued=once('SYNC_WMS_FAILURE',API+'/directory/sync',actual)['data']
    failed_sync=wait(lambda:next(r for r in api(API+'/sync/list?'+urllib.parse.urlencode(dict(system_type='WMS',connection_id=w['connection_id'],pageSize=100)))['data']['rows'] if r['id']==queued['id']),lambda r:r['state'] in ['SUCCESS','FAILED'],'目录后台任务执行')
    assert failed_sync['state']=='FAILED','本地模拟器未实现目录查询，不应虚构官方目录'
    assert api(API+'/mapping/list?'+urllib.parse.urlencode(dict(system_type='WMS',connection_id=w['connection_id'],pageSize=100)))['data']==mappings_before
    check('A13','未接通目录适配器明确提示不支持；奇门TEST目录查询失败正常落为失败任务，均保留已确认目录及对照',dict(channel=synced,wms=failed_sync))
    parent=doc();line=parent['execution_lines'][0]
    once('SUPPLIER_REFUSAL',RET+'/'+str(returned)+'/supplier-receipt',dict(request_key='SC_REFUSE_'+stamp,control_version=parent['control_version'],decision='REFUSE',evidence='[TEST] 供应商实际拒收1件凭证；仅登记事实',lines=[dict(execution_line_id=line['id'],quantity=1)]))
    parent=doc();refusal=next(s for s in parent['supplier_sources'] if s['decision']=='REFUSE')
    reclaim=once('RAW_RECLAIM',RET+'/'+str(returned)+'/reclaims',dict(request_key='SC_RAW_RETURN_'+stamp,control_version=parent['control_version'],carrier_unverified=True,raw_carrier_code='001/Unknown.a',raw_carrier_name='[TEST] 供应商凭证未知承运商',waybill='TEST-SUPPLIER-RETURN-'+stamp,reason='[TEST] 保留原始物流，标准身份待凭证核实；不得自动下发',lines=[dict(supplier_source_id=refusal['id'],quantity=1)]))['data']
    raw=api(RET+'/reclaims/'+str(reclaim))['data'];assert raw['state']=='DRAFT' and not raw['standard_id'] and raw['raw_carrier_code']=='001/Unknown.a' and raw['carrier_code']==''
    blocked=fails(RET+'/reclaims/'+str(reclaim)+'/approve',dict(control_version=raw['control_version']),'凭证确认标准快递')
    assert stock()==state['stock_posted'];state['ids']['raw_reclaim']=reclaim;save()
    check('A17','供应商未知寄回快递可保存原始编码、名称和运单为待核实草稿；未核实无法审核下发，登记拒收及物流均不增加库存',dict(reclaim_sn=raw['reclaim_sn'],raw_carrier_code=raw['raw_carrier_code'],raw_carrier_name=raw['raw_carrier_name'],state=raw['state'],blocked=blocked,stock=stock()))
    state['stage']='CONFIG_AND_PHYSICAL_VERIFIED';save();print('Retained carrier acceptance data:',state['return_sn'],flush=True)


if __name__=='__main__':main()
