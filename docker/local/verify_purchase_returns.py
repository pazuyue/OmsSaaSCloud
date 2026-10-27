"""Retained local TEST acceptance: explicit approvals, automatic stock/WMS execution."""
import hashlib,json,time,urllib.request,urllib.parse,xml.etree.ElementTree as ET
from pathlib import Path
from datetime import datetime,timedelta,timezone
from concurrent.futures import ThreadPoolExecutor
from verify_goods_workspace_ui import login,request,BASE
from verify_return_management import protected
from test_multi_virtual_shipping import sql,quote
from wms_qimen_simulator import sign

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/purchase-returns';SC='/supplychain';PO=SC+'/purchaseWorkspace';RET=SC+'/purchaseReturns';Q='/inventory/qualityInventory';INV='qm_oms_saas_inventory'
DEFINITIONS=[('virtual','虚拟仓退供3件，审核后自动扣账',10,0),('accepted','真实仓已受理，尚未出库保持原锁定',10,0),('full','真实仓退供3件并自动扣账',10,0),('partial','申请5件只实出2件，保留剩余3件占用',10,0),('split','5件分两次出库2件加3件',10,0),('bad','采购实收正品3次品2，次品2件全部退供',3,2),('mixed','同一退仓单包含正品2件及次品1件',3,2),('replay','同一出库消息重复回传不重复扣账',10,0),('over','申请3件却回传4件，保留待核对且不扣账',10,0),('wrong_batch','原批次不匹配，保留原占用并等待更正',10,0),('cancel','仓库受理后停止退仓，释放全部未出库数量',10,0),('partial_cancel','实出2件后停止剩余3件，只扣2件',10,0),('concurrent','原实收10件并发审核两张各7件退供，仅一张成功',10,0),('freeze','正品4件冻结，库存总量不变可售减少',10,0),('unfreeze','冻结正品4件后按原来源解冻2件',10,0),('convert','仓库质检后次品2件转正，总库存不变',3,2),('reject','质量申请被驳回，库存不变化',10,0),('bad_freeze','次品冻结再解冻，不转为正品或重复冻结汇总',3,2),('funds','退供实出3件后登记7.50元抵扣凭证',10,0),('dispatch_fail','奇门模拟仓明确拒绝下发，保留原批次锁定',10,0),('final_short','仓库最终实出2件后结束，保留申请5件中的剩余占用',10,0)]


def main():
    OUT.mkdir(exist_ok=True);path=OUT/'manifest.json';d=json.loads(path.read_text('utf-8')) if path.exists() else dict(stamp=datetime.now().strftime('%Y%m%d%H%M%S'),cases=[]);stamp=d['stamp'];token=login()
    def save():path.write_text(json.dumps(d,ensure_ascii=False,indent=2),'utf-8')
    def api(path,body=None):return request(path,payload=body,token=token)
    def wait(fn,ok,label):
        for _ in range(90):
            try:value=fn()
            except (AssertionError,urllib.error.URLError):
                time.sleep(1);continue
            if ok(value):return value
            time.sleep(1)
        raise AssertionError(label)
    def snapshot(sku):
        company=sql("SELECT COALESCE(SUM(total_stock),0),COALESCE(SUM(available_stock),0),COALESCE(SUM(allocated_stock),0),COALESCE(SUM(reserved_stock),0),COALESCE(SUM(frozen_stock),0) FROM oms_inventory WHERE company_code='QM' AND sku_sn="+quote(sku),INV).split('\t')
        warehouse=sql("SELECT COALESCE(SUM(zp_actual_number),0),COALESCE(SUM(cp_actual_number),0),COALESCE(SUM(zp_available_number),0),COALESCE(SUM(cp_available_number),0),COALESCE(SUM(zp_lock_number),0),COALESCE(SUM(cp_lock_number),0) FROM wms_inventory WHERE company_code='QM' AND sku_sn="+quote(sku),INV).split('\t')
        batch=sql("SELECT COALESCE(SUM(zp_actual_number),0),COALESCE(SUM(cp_actual_number),0),COALESCE(SUM(zp_available_number),0),COALESCE(SUM(cp_available_number),0),COALESCE(SUM(zp_lock_number),0),COALESCE(SUM(cp_lock_number),0) FROM wms_inventory_batch WHERE company_code='QM' AND sku_sn="+quote(sku),INV).split('\t')
        assert warehouse==batch,(sku,warehouse,batch)
        return dict(company=dict(zip(['total','available','allocated','reserved','frozen'],map(int,company))),warehouse=dict(zip(['good','bad','good_free','bad_free','good_locked','bad_locked'],map(int,warehouse))),batch=dict(zip(['good','bad','good_free','bad_free','good_locked','bad_locked'],map(int,batch))))
    def first_round_baseline():
        m=ROOT/'outputs/supplychain-iteration/manifest.json';skus=[c['sku'] for c in json.loads(m.read_text('utf-8'))['cases']];inlist=','.join(quote(s) for s in skus)
        return dict(manifest_sha256=hashlib.sha256(m.read_bytes()).hexdigest(),stock=sql("SELECT sku_sn,total_stock,available_stock,allocated_stock,reserved_stock,frozen_stock FROM oms_inventory WHERE company_code='QM' AND sku_sn IN("+inlist+") ORDER BY sku_sn",INV),warehouse=sql("SELECT sku_sn,store_code,zp_actual_number,cp_actual_number,zp_available_number,cp_available_number,zp_lock_number,cp_lock_number FROM wms_inventory WHERE company_code='QM' AND sku_sn IN("+inlist+") ORDER BY sku_sn,store_code",INV))
    if 'protected' not in d:d['protected']=protected();d['first_round_baseline']=first_round_baseline();save()
    wait(lambda:api(RET)['data'],lambda x:'rows' in x,'New supplychain route not ready');wait(lambda:api(Q)['data'],lambda x:'rows' in x,'Quality route not ready')
    if not d.get('owner'):d['owner']=api(SC+'/owner',dict(ownerCode='PRQO_'+stamp,ownerName='[退仓质量验收] '+stamp,isEnable=2))['data'];save()
    if not d.get('warehouse'):d['warehouse']=api(SC+'/realStore',dict(realStoreCode='PRQW_'+stamp,wmsName='[退仓质量验收] TEST仓 '+stamp,wmsType=1,status=2))['data'];save()
    if not d.get('relation'):d['relation']=api(SC+'/ownerWarehouse',dict(ownerId=d['owner'],realStoreId=d['warehouse'],status=2))['data'];save()
    credential=json.loads((ROOT/'docker/local/wms-simulator-credentials.json').read_text('utf-8'))[0]
    for mode in ['real','virtual','reject']:
        if mode!='virtual' and not d.get('connection_'+mode):
            valid=mode=='real';d['connection_'+mode]=api(SC+'/wmsIntegration/connections',dict(name='[退仓质量验收] '+mode+' '+stamp,provider='QIMEN',api_version='2.0',environment='TEST',endpoint='http://wms-simulator:8080/qimen/gateway',app_key=credential['app_key'] if valid else 'PRQ_REJECT_'+stamp,customer_id=credential['customer_id'] if valid else 'PRQ_REJECT_'+stamp,secret=credential['secret'] if valid else 'LOCAL-ONLY-REJECT-CREDENTIAL',enabled=1,partial_inbound_cancel=1))['data'];save()
        if not d.get('store_'+mode):
            code='PRQ_'+mode.upper()+'_'+stamp;body=dict(wmsSimulationCode=code,wmsSimulationName='[退仓质量验收] '+mode+' '+stamp,ownerWarehouseId=d['relation'],inboundMode=1 if mode=='real' else 2,outboundMode=2 if mode=='virtual' else 1,status=2)
            if mode!='virtual':body.update(connectionId=d['connection_'+mode],externalWarehouse='PRQ_EXT_'+stamp,externalOwner='PRQ_OWNER_'+stamp)
            api(SC+'/simulationStore',body);d['store_'+mode]=code;save()
    if not d.get('supplier'):d['supplier']=api(PO+'/supplier',dict(supplierName='[退仓质量验收] '+stamp,companyName='TEST供应商 '+stamp,status=2))['data'];d['supplier_sn']=api(PO+'/supplier/'+str(d['supplier']))['data']['supplierSn'];save()
    if not d['cases']:d['cases']=[dict(key=k,title=title,initial_good=g,initial_bad=b,sku='PRQ_'+stamp+'_'+str(i).zfill(2),sent=[]) for i,(k,title,g,b) in enumerate(DEFINITIONS,1)];save()
    known={c['key'] for c in d['cases']}
    for i,(k,title,g,b) in enumerate(DEFINITIONS,1):
        if k not in known:d['cases'].append(dict(key=k,title=title,initial_good=g,initial_bad=b,sku='PRQ_'+stamp+'_'+str(i).zfill(2),sent=[]));save()
    catalog=api('/goods/info/options')['data'];connections={x['id']:x for x in api(SC+'/wmsIntegration/connections')['data']}
    def wire(connection,method,root,expected='success'):
        assert connection['environment']=='TEST' and connection['endpoint']=='http://wms-simulator:8080/qimen/gateway'
        body=ET.tostring(root,encoding='unicode');p=dict(method=method,timestamp=datetime.now(timezone(timedelta(hours=8))).strftime('%Y-%m-%d %H:%M:%S'),format='xml',app_key=credential['app_key'],customerId=credential['customer_id'],v='2.0',sign_method='md5');p['sign']=sign(p,body,credential['secret']);url=BASE+'/prod-api'+SC+'/wmsCallback/qm/'+connection['callback_key']+'?'+urllib.parse.urlencode(p)
        try:r=ET.fromstring(urllib.request.urlopen(urllib.request.Request(url,data=body.encode('utf-8'),headers={'Content-Type':'application/xml'},method='POST'),timeout=40).read())
        except Exception:raise RuntimeError('TEST callback outcome not confirmed; original identity retained') from None
        assert r.findtext('flag')==expected,r.findtext('message')
    def tags(parent,values):
        for k,v in values.items():ET.SubElement(parent,k).text=str(v)
    def inbound(c):
        if c.get('inbound_sent'):return
        task=wait(lambda:api(SC+'/wmsIntegration/tickets/'+str(c['ticket_id']))['data'],lambda x:x['dispatch_state']=='ACCEPTED','Automatic purchase dispatch not accepted');root=ET.Element('request');h=ET.SubElement(root,'entryOrder');count=1+int(c['initial_good']>0 and c['initial_bad']>0);tags(h,dict(entryOrderCode=task['ticket_sn'],entryOrderId=task['external_order'],warehouseCode=task['external_warehouse'],ownerCode=task['external_owner'],status='FULFILLED',confirmType=0,outBizCode='PRQ_IN_'+c['key'],totalOrderLines=count));ls=ET.SubElement(root,'orderLines')
        for q,grade in [(c['initial_good'],'ZP'),(c['initial_bad'],'CC')]:
            if q:tags(ET.SubElement(ls,'orderLine'),dict(itemCode=c['sku'],ownerCode=task['external_owner'],batchCode='PRQ_BATCH_'+c['key'],actualQty=q,inventoryType=grade))
        wire(connections[task['connection_id']],'taobao.qimen.entryorder.confirm',root);c['inbound_sent']=True;save()
    def outbound(c,message,quantities,complete=True,expected='success',wrong_batch=False,replay=False):
        if message in c['sent'] and not replay:return
        doc=api(RET+'/'+str(c['return_id']))['data'];payload=json.loads(sql("SELECT payload_json FROM purchase_return WHERE company_code='QM' AND id="+str(c['return_id']),'qm_oms_saas_commodity'));route=payload['route'];root=ET.Element('request');h=ET.SubElement(root,'deliveryOrder');tags(h,dict(deliveryOrderCode=doc['return_sn'],deliveryOrderId=doc['external_order'],orderType='CGTH',ownerCode=route['external_owner'],warehouseCode=route['external_warehouse'],outBizCode=message,status='DELIVERED' if complete else 'PARTDELIVERED',confirmType=0 if complete else 1,totalOrderLines=len(quantities),logisticsCode='SF',logisticsName='[TEST]顺丰',expressCode='PRQ_WB_'+stamp+c['key'],orderConfirmTime=datetime.now().strftime('%Y-%m-%d %H:%M:%S')));ls=ET.SubElement(root,'orderLines')
        for line,q in zip(doc['lines'],quantities):tags(ET.SubElement(ls,'orderLine'),dict(orderLineNo=line['id'],itemCode=line['sku_sn'],ownerCode=route['external_owner'],batchCode='WRONG_BATCH' if wrong_batch else line['batch_code'],inventoryType='CC' if line['inventory_type']=='CP' else 'ZP',actualQty=q))
        # A repeated business message must repeat the exact content, including outbound time.
        if replay:root=ET.fromstring(c['outbound_bodies'][message])
        else:c.setdefault('outbound_bodies',{})[message]=ET.tostring(root,encoding='unicode');save()
        wire(connections[route['connection_id']],'taobao.qimen.stockout.confirm',root,expected)
        if not replay:c['sent'].append(message);save()
    def new_return(c,source,quantities,request_key=None):
        selected=[]
        for grade,qty in quantities:
            l=next(x for x in source['rows'] if x['inventory_type']==grade);selected.append(dict(source_key=l['source_key'],inventory_type=grade,quantity=qty))
        return api(RET,dict(request_key=request_key or 'PRQ_'+stamp+'_'+c['key'],purchase_id=c['purchase_id'],receiver=dict(name='[TEST]供应商收货',mobile='13000000000',province='浙江省',city='杭州市',area='测试区',detailAddress='本地验收测试供应商地址'),carrier_code='SF',reason='[退仓质量验收] '+c['title'],lines=selected))['data']
    def quality(c,action,qty,grade='ZP',source=None,reject=False):
        label=action+('_REJECT' if reject else '')
        if not c.get('quality_'+label):
            batches=api(Q+'/batches?'+urllib.parse.urlencode(dict(sku=c['sku'],store=c['store'])))['data'];assert len(batches)==1
            c['quality_'+label]=api(Q,dict(request_key='PRQ_'+stamp+'_'+c['key']+'_'+label,action=action,batch_id=batches[0]['id'],quantity=qty,inventory_type=grade,source_sn=source or '',reason='[验收] '+c['title'],evidence='[本地TEST模拟质检] PRQ-QA-'+stamp,warehouse_confirmed=action=='CP_TO_ZP'))['data'];save()
        q=api(Q+'/'+str(c['quality_'+label]))['data']
        if q['state']=='DRAFT':q=api(Q+'/'+str(q['id'])+'/review',dict(control_version=q['control_version'],decision='REJECT' if reject else 'APPROVE',reason='[验收] 已核对原批次库存及仓库质检依据'))['data']
        return q
    for c in d['cases']:
        key=c['key'];mode='virtual' if key=='virtual' else 'reject' if key=='dispatch_fail' else 'real';c['store']=d['store_'+mode]
        if not c.get('product'):c['product']=api('/goods/info',dict(skuSn=c['sku'],goodsSn=c['sku'],goodsName='[退仓质量验收] '+c['title'],marketPrice=10,validity=30,categoryCode=next(x['id'] for x in catalog['category'] if x['level']==3),colorCode=catalog['color'][0]['id'],sizeCode=catalog['size'][0]['id']))['data'];save()
        if not c.get('purchase_id'):c['purchase_id']=api(PO+'/purchase',dict(poName='[退仓质量验收] '+c['title'],supplierSn=d['supplier_sn'],wmsSimulationCode=c['store'],lines=[dict(skuSn=c['sku'],quantity=c['initial_good']+c['initial_bad'],purchasePrice=2.5)]))['data'];save()
        p=api(PO+'/purchase/'+str(c['purchase_id']))['data'];c['purchase_sn']=p['poSn']
        if p['poState']==1:api(PO+'/purchase/'+str(c['purchase_id'])+'/approve',{})
        if not c.get('receipt_id'):c['receipt_id']=api(PO+'/purchase/'+str(c['purchase_id'])+'/receipt',dict(lines=[dict(lineId=p['lines'][0]['id'],quantity=c['initial_good']+c['initial_bad'])]))['data'];save()
        if not c.get('ticket_id'):
            r=api(PO+'/receipt/'+str(c['receipt_id']))['data'];c['ticket_id']=r['tickets'][0]['id'] if r['tickets'] else api(PO+'/receipt/'+str(c['receipt_id'])+'/approve',{})['data'];save()
        if mode=='real':inbound(c)
        wait(lambda:api(PO+'/ticket/'+str(c['ticket_id']))['data'],lambda x:x['numberPosted']==c['initial_good']+c['initial_bad'],'Automatic purchase posting incomplete')
        if not c.get('initial_stock'):c['initial_stock']=snapshot(c['sku']);assert c['initial_stock']['company']==dict(total=c['initial_good']+c['initial_bad'],available=c['initial_good'],allocated=0,reserved=0,frozen=c['initial_bad']);save()
        good=c['initial_good'];bad=c['initial_bad'];good_lock=bad_lock=quality_good=quality_bad=0;returned_good=returned_bad=0
        if key in ['freeze','unfreeze','convert','reject','bad_freeze']:
            if key=='convert':quality(c,'CP_TO_ZP',2,'CP');good+=2;bad-=2
            else:
                freeze=quality(c,'FREEZE',1 if key=='bad_freeze' else 4,'CP' if key=='bad_freeze' else 'ZP',reject=key=='reject')
                if key=='freeze':good_lock=quality_good=4
                elif key=='unfreeze':quality(c,'UNFREEZE',2,source=freeze['quality_sn']);good_lock=quality_good=2
                elif key=='bad_freeze':quality(c,'UNFREEZE',1,'CP',source=freeze['quality_sn'])
            c['quality_results']=[api(Q+'/'+str(v))['data'] for k,v in c.items() if k.startswith('quality_') and isinstance(v,int)]
        else:
            quantities=[('CP',2)] if key=='bad' else [('ZP',2),('CP',1)] if key=='mixed' else [('ZP',5)] if key in ['partial','split','cancel','partial_cancel','final_short'] else [('ZP',7)] if key=='concurrent' else [('ZP',3)]
            if not c.get('return_id'):
                source=api(RET+'/sources?purchaseId='+str(c['purchase_id']))['data'];c['return_id']=new_return(c,source,quantities);save()
                if key=='concurrent':c['second_return_id']=new_return(c,source,quantities,'PRQ_'+stamp+'_CONCURRENT_SECOND');save()
            doc=api(RET+'/'+str(c['return_id']))['data'];c['return_sn']=doc['return_sn']
            if key=='concurrent' and not c.get('concurrency'):
                def approve(ident):
                    try:api(RET+'/'+str(ident)+'/approve',dict(control_version=1));return dict(id=ident,accepted=True)
                    except AssertionError:return dict(id=ident,accepted=False)
                with ThreadPoolExecutor(max_workers=2) as pool:answers=list(pool.map(approve,[c['return_id'],c['second_return_id']]))
                assert sum(x['accepted'] for x in answers)==1,answers;c['concurrency']=answers;c['return_id']=next(x['id'] for x in answers if x['accepted']);save()
            elif doc['state']=='DRAFT':api(RET+'/'+str(c['return_id'])+'/approve',dict(control_version=doc['control_version']))
            doc=wait(lambda:api(RET+'/'+str(c['return_id']))['data'],lambda x:x['state']=='COMPLETED' if mode=='virtual' else x['dispatch_state'] in ['ACCEPTED','FAILED','CANCELED'],'Automatic stock lock and warehouse dispatch incomplete');c['return_sn']=doc['return_sn']
            msg='PRQ_OUT_'+stamp+'_'+key
            if key in ['full','replay','funds']:outbound(c,msg,[3]);returned_good=3
            elif key=='bad':outbound(c,msg,[2]);returned_bad=2
            elif key=='mixed':outbound(c,msg,[2,1]);returned_good=2;returned_bad=1
            elif key in ['partial','partial_cancel','final_short']:outbound(c,msg,[2],complete=key=='final_short');returned_good=2
            elif key=='split':outbound(c,msg+'_1',[2],complete=False);outbound(c,msg+'_2',[3]);returned_good=5
            elif key=='over':outbound(c,msg,[4],expected='failure')
            elif key=='wrong_batch':outbound(c,msg,[3],expected='failure',wrong_batch=True)
            elif key=='virtual':returned_good=3
            if key=='mixed':
                before=api(RET+'/'+str(c['return_id']))['data']
                if any(e['state']=='FAILED' for e in before['events']):
                    c.setdefault('resolved_issues',[]).append(dict(problem='同SKU同批次正次品扣账流水幂等键冲突，事务已整体回滚',fix='幂等键包含退仓单、原业务行、正次品与动作；重放原回传自动重试原失败事件',failed_snapshot=before['last_error']));outbound(c,msg,[2,1],replay=True);save()
            if returned_good+returned_bad:doc=wait(lambda:api(RET+'/'+str(c['return_id']))['data'],lambda x:x['posted_quantity']==returned_good+returned_bad,'Automatic purchase return stock posting incomplete')
            if key=='replay':outbound(c,msg,[3],replay=True)
            if key in ['cancel','partial_cancel']:
                doc=api(RET+'/'+str(c['return_id']))['data']
                if doc['state']!='CANCELED':api(RET+'/'+str(c['return_id'])+'/cancel',dict(control_version=doc['control_version'],reason='[验收] 停止剩余退仓，已出库部分保留'))
                doc=wait(lambda:api(RET+'/'+str(c['return_id']))['data'],lambda x:x['state']=='CANCELED','Warehouse cancellation and source release incomplete')
            if key=='funds':
                doc=api(RET+'/'+str(c['return_id']))['data']
                if doc['funds_state']=='UNRECORDED':api(RET+'/'+str(c['return_id'])+'/funds',dict(request_key='PRQ_FUNDS_'+stamp+'_'+key,control_version=doc['control_version'],funds_state='CREDIT',amount=7.5,evidence='[本地验收] 已核对供应商抵扣凭证，非银行调用'))
            doc=api(RET+'/'+str(c['return_id']))['data'];c['final_return']=doc
            if key in ['over','wrong_batch']:assert doc['receipt_review_pending']==1 and doc['accepted_quantity']==0 and doc['posted_quantity']==0
            if key=='dispatch_fail':assert doc['dispatch_state']=='FAILED' and doc['posted_quantity']==0
            if key=='accepted':assert doc['state']=='APPROVED' and doc['accepted_quantity']==0
            if key not in ['cancel','partial_cancel']:
                good_lock=sum(q for grade,q in quantities if grade=='ZP')-returned_good;bad_lock=sum(q for grade,q in quantities if grade=='CP')-returned_bad
            good-=returned_good;bad-=returned_bad
        c['final_stock']=snapshot(c['sku']);w=c['final_stock']['warehouse'];company=c['final_stock']['company'];expected_wh=dict(good=good,bad=bad,good_free=good-good_lock,bad_free=bad-bad_lock,good_locked=good_lock,bad_locked=bad_lock);assert w==expected_wh,(key,w,expected_wh)
        expected_company=dict(total=good+bad,available=good-good_lock,allocated=good_lock+bad_lock-quality_good-quality_bad,reserved=0,frozen=bad-bad_lock+quality_good+quality_bad);assert company==expected_company,(key,company,expected_company)
        c['final_purchase']=api(PO+'/purchase/'+str(c['purchase_id']))['data'];assert c['final_purchase']['journey']['posted']==c['initial_good']+c['initial_bad']
        c['inventory_ledger']=sql("SELECT operation_type,relation_sn,change_quantity,inventory_type,old_zp_actual_number,new_zp_actual_number,old_cp_actual_number,new_cp_actual_number FROM wms_inventory_change_history WHERE company_code='QM' AND sku_sn="+quote(c['sku'])+' ORDER BY log_id',INV)
        c['url']=BASE+('/oms-supplychain/purchaseReturns?id='+str(c['return_id']) if c.get('return_id') else '/oms-inventory/quality?sku='+c['sku']);c['result']='PASS';save();print('PASS',key,'stock',company,flush=True)
    assert protected()==d['protected'],'Protected order/refund/queue/job baseline changed';assert first_round_baseline()==d['first_round_baseline'],'Previous procurement acceptance changed';d['protected_baseline_unchanged']=True;d['completed_at']=datetime.now().isoformat();save();print('PASS',len(d['cases']),'retained purchase-return and quality scenarios')


if __name__=='__main__':main()
