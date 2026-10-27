"""Retained procurement acceptance using new TEST-only suppliers/warehouses/SKUs.

Approvals and difference decisions are explicit operator steps; warehouse dispatch and stock
posting must run through the application's real automatic workers. No original business row,
inventory balance, global scheduler or queue policy is edited.
"""
import json
import time
import urllib.request
import urllib.parse
import xml.etree.ElementTree as ET
from datetime import datetime,timedelta,timezone
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from verify_goods_workspace_ui import login,request,BASE
from verify_return_management import protected
from test_multi_virtual_shipping import sql,quote
from wms_qimen_simulator import sign

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/supplychain-iteration';API='/supplychain/purchaseWorkspace'
SCENARIOS=[
 ('draft','采购草稿100件，未审核未安排到货',100,0),('scheduled','采购100件，本批安排60件待审核，未安排40件',100,60),
 ('virtual','虚拟仓审核10件后自动收货并入账',10,10),('full','真实仓完整回传5件并自动入账',5,5),
 ('partial','安排10件只回传4件非最终收货，保留待收6件',10,10),('split','5件分两次回传2件和3件，分别入账',5,5),
 ('short','安排10件最终只收8件，保留短收2件待处理',10,10),('rearrange','最终短收2件后并发点击补建，只创建一张2件到货单',10,10),
 ('bad','5件中正品3件次品2件，按质量分别入账',5,5),('bad_keep','次品2件入账后确认留库，库存不重复增加',5,5),
 ('over','计划5件回传7件，超收回传保留但不实收不入账',5,5),('wrong','计划A商品5件却回传B商品，保留错货但不入账',5,5),
 ('corrected','错货回传后仓库按原消息更正为A商品5件',5,5),('replay','同一5件实收回传两次，只入账一次',5,5),
 ('dispatch_fail','TEST仓库明确拒绝下发，保留失败待办和原因',5,5),('late','正常申请截单后又收到2件回传，保留待核对且不入账',5,5),
 ('concurrent','采购100件并发安排两批各60件，只允许一批成功',100,60),('overdue','采购预计日期已过且尚未安排到货，显示逾期待办',10,0)]


def main():
    OUT.mkdir(exist_ok=True);path=OUT/'manifest.json';data=json.loads(path.read_text('utf-8')) if path.exists() else {'stamp':datetime.now().strftime('%Y%m%d%H%M%S'),'cases':[]};stamp=data['stamp'];token=login()
    def save():path.write_text(json.dumps(data,ensure_ascii=False,indent=2),'utf-8')
    def api(path,payload=None):return request(path,payload=payload,token=token)
    def get(kind,key):return api(API+'/'+kind+'/'+str(key))['data']
    def wait(fn,predicate,label):
        for attempt in range(90):
            value=fn()
            if predicate(value):return value
            time.sleep(1)
        raise AssertionError(label)
    def balances(sku):
        warehouse=sql("SELECT COALESCE(SUM(zp_actual_number),0),COALESCE(SUM(cp_actual_number),0),COALESCE(SUM(zp_available_number),0),COALESCE(SUM(cp_available_number),0),COALESCE(SUM(zp_lock_number+cp_lock_number),0) FROM wms_inventory WHERE company_code='QM' AND sku_sn="+quote(sku),'qm_oms_saas_inventory').split('\t')
        return dict(zip(['good','bad','good_available','bad_available','locked'],map(int,warehouse)))
    for attempt in range(45):
        try:
            api(API+'/operations?number=SCIT_READY')
            api('/goods/info/options')
            break
        except Exception:
            if attempt==44:raise
            time.sleep(1)
    def inventory_layers(sku,good,bad):
        company=sql("SELECT COALESCE(SUM(total_stock),0),COALESCE(SUM(available_stock),0),COALESCE(SUM(reserved_stock),0),COALESCE(SUM(allocated_stock),0),COALESCE(SUM(frozen_stock),0) FROM oms_inventory WHERE company_code='QM' AND sku_sn="+quote(sku),'qm_oms_saas_inventory').split('\t')
        company=dict(zip(['total','available','reserved','allocated','frozen'],map(int,company)))
        assert company==dict(total=good+bad,available=good+bad,reserved=0,allocated=0,frozen=0),(sku,company)
        batch=sql("SELECT COALESCE(SUM(zp_actual_number),0),COALESCE(SUM(cp_actual_number),0),COALESCE(SUM(zp_available_number),0),COALESCE(SUM(cp_available_number),0),COALESCE(SUM(zp_lock_number+cp_lock_number),0) FROM wms_inventory_batch WHERE company_code='QM' AND sku_sn="+quote(sku),'qm_oms_saas_inventory').split('\t')
        batch=dict(zip(['good','bad','good_available','bad_available','locked'],map(int,batch)))
        assert batch==dict(good=good,bad=bad,good_available=good,bad_available=bad,locked=0),(sku,batch)
        history=sql("SELECT request_id,change_quantity FROM wms_inventory_change_history WHERE company_code='QM' AND sku_sn="+quote(sku)+" AND operation_type='RECEIVE' ORDER BY log_id",'qm_oms_saas_inventory')
        entries=[dict(relation=line.split('\t')[0],quantity=int(line.split('\t')[1])) for line in history.splitlines() if line.strip()]
        assert sum(x['quantity'] for x in entries)==good+bad,(sku,entries)
        return dict(company=company,batches=batch,ledger=entries)
    if 'protected' not in data:data['protected']=protected();save()
    if not data.get('owner'):data['owner']=api('/supplychain/owner',dict(ownerCode='SCITO_'+stamp,ownerName='[供应链迭代验收] '+stamp,isEnable=2))['data'];save()
    if not data.get('warehouse'):data['warehouse']=api('/supplychain/realStore',dict(realStoreCode='SCITW_'+stamp,wmsName='[供应链迭代验收] TEST仓 '+stamp,wmsType=1,status=2))['data'];save()
    if not data.get('relation'):data['relation']=api('/supplychain/ownerWarehouse',dict(ownerId=data['owner'],realStoreId=data['warehouse'],status=2))['data'];save()
    credential=json.loads((ROOT/'docker/local/wms-simulator-credentials.json').read_text('utf-8'))[0]
    for mode in ['real','virtual','reject']:
        if mode!='virtual' and not data.get('connection_'+mode):
            key=api('/supplychain/wmsIntegration/connections',dict(name='[供应链迭代验收] '+mode+' '+stamp,provider='QIMEN',api_version='2.0',environment='TEST',endpoint='http://wms-simulator:8080/qimen/gateway',app_key=credential['app_key'] if mode=='real' else 'SCIT_REJECT_'+stamp,customer_id=credential['customer_id'] if mode=='real' else 'SCIT_REJECT_'+stamp,secret=credential['secret'] if mode=='real' else 'LOCAL-ONLY-REJECT-CREDENTIAL',enabled=1,partial_inbound_cancel=1))['data'];data['connection_'+mode]=key;save()
        if not data.get('virtual_'+mode):
            code='SCIT_'+mode.upper()+'_'+stamp;body=dict(wmsSimulationCode=code,wmsSimulationName='[供应链迭代验收] '+mode+' '+stamp,ownerWarehouseId=data['relation'],inboundMode=2 if mode=='virtual' else 1,outboundMode=2,status=2)
            if mode!='virtual':body.update(connectionId=data['connection_'+mode],externalWarehouse='SCIT_EXT_'+stamp,externalOwner='SCIT_OWNER_'+stamp)
            api('/supplychain/simulationStore',body);data['virtual_'+mode]=code;save()
    if not data.get('supplier_id'):
        data['supplier_id']=api(API+'/supplier',dict(supplierName='[供应链迭代验收] '+stamp,companyName='[供应链迭代验收] 测试供应商 '+stamp,status=2))['data'];data['supplier_sn']=get('supplier',data['supplier_id'])['supplierSn'];save()
    catalog=api('/goods/info/options')['data']
    if not data['cases']:
        data['cases']=[dict(key=key,title=title,planned=planned,arranged=arranged,sku='SCIT_'+stamp+'_'+str(i).zfill(2)+'A',wrong_sku='SCIT_'+stamp+'_'+str(i).zfill(2)+'B',sent=[]) for i,(key,title,planned,arranged) in enumerate(SCENARIOS,1)];save()
    def product(sku,title):return api('/goods/info',dict(skuSn=sku,goodsSn=sku,goodsName='[供应链迭代验收] '+title,marketPrice=10,validity=30,categoryCode=next(x['id'] for x in catalog['category'] if x['level']==3),colorCode=catalog['color'][0]['id'],sizeCode=catalog['size'][0]['id']))['data']
    connections={x['id']:x for x in api('/supplychain/wmsIntegration/connections')['data']}
    def callback(c,message,good,bad=0,complete=True,wrong=False,expected='success'):
        sent_key=message+':WRONG' if wrong else message
        if sent_key in c['sent']:return
        t=c['task'];root=ET.Element('request');head=ET.SubElement(root,'entryOrder')
        for k,v in dict(entryOrderCode=t['ticket_sn'],entryOrderId=t['external_order'],warehouseCode=t['external_warehouse'],ownerCode=t['external_owner'],status='FULFILLED' if complete else 'PARTFULFILLED',confirmType='0' if complete else '1',outBizCode=message,totalOrderLines=1).items():ET.SubElement(head,k).text=str(v)
        line=ET.SubElement(ET.SubElement(root,'orderLines'),'orderLine')
        for k,v in dict(itemCode=c['wrong_sku'] if wrong else c['sku'],ownerCode=t['external_owner'],batchCode='SCIT_BATCH_'+c['key'],actualQty=good+bad,inventoryType='ZP').items():ET.SubElement(line,k).text=str(v)
        # Split grades into inventory items as supported by the Qimen adapter.
        if bad:
            line.find('actualQty').text=str(good)
            other=ET.SubElement(root.find('orderLines'),'orderLine')
            for k,v in dict(itemCode=c['sku'],ownerCode=t['external_owner'],batchCode='SCIT_BATCH_'+c['key'],actualQty=bad,inventoryType='CC').items():ET.SubElement(other,k).text=str(v)
            head.find('totalOrderLines').text='2'
        body=ET.tostring(root,encoding='unicode');connection=connections[t['connection_id']];assert connection['environment']=='TEST' and connection['endpoint']=='http://wms-simulator:8080/qimen/gateway'
        params=dict(method='taobao.qimen.entryorder.confirm',timestamp=datetime.now(timezone(timedelta(hours=8))).strftime('%Y-%m-%d %H:%M:%S'),format='xml',app_key=credential['app_key'],customerId=credential['customer_id'],v='2.0',sign_method='md5');params['sign']=sign(params,body,credential['secret'])
        url=BASE+'/prod-api/supplychain/wmsCallback/qm/'+connection['callback_key']+'?'+urllib.parse.urlencode(params)
        try:
            response=urllib.request.urlopen(urllib.request.Request(url,data=body.encode('utf-8'),headers={'Content-Type':'application/xml'},method='POST'),timeout=40);reply=ET.fromstring(response.read())
        except Exception:raise RuntimeError('TEST callback result not confirmed; original message identity retained') from None
        assert reply.findtext('flag')==expected,(c['key'],reply.findtext('message'));c['sent'].append(sent_key);save()
    for c in data['cases']:
        key=c['key']
        if not c.get('product'):c['product']=product(c['sku'],c['title']);save()
        if key in ['wrong','corrected'] and not c.get('wrong_product'):c['wrong_product']=product(c['wrong_sku'],'错货商品 '+key);save()
        if not c.get('purchase_id'):
            c['initial_stock']=balances(c['sku']);assert c['initial_stock']['good']==c['initial_stock']['bad']==0
            body=dict(poName='[供应链迭代验收] '+c['title']+' '+stamp,supplierSn=data['supplier_sn'],wmsSimulationCode=data['virtual_virtual' if key=='virtual' else 'virtual_reject' if key=='dispatch_fail' else 'virtual_real'],lines=[dict(skuSn=c['sku'],quantity=c['planned'],purchasePrice=2.5)])
            if key=='overdue':body['expectedDate']=(datetime.now()-timedelta(days=1)).strftime('%Y-%m-%d')
            c['purchase_id']=api(API+'/purchase',body)['data'];save()
        p=get('purchase',c['purchase_id']);c['purchase_sn']=p['poSn']
        if key!='draft' and p['poState']==1:api(API+'/purchase/'+str(c['purchase_id'])+'/approve',{})
        if c['arranged'] and not c.get('receipt_id'):
            p=get('purchase',c['purchase_id']);body=dict(lines=[dict(lineId=p['lines'][0]['id'],quantity=c['arranged'])])
            if key=='concurrent':
                def arrange(_):
                    try:return {'accepted':True,'id':api(API+'/purchase/'+str(c['purchase_id'])+'/receipt',body)['data']}
                    except Exception:return {'accepted':False}
                with ThreadPoolExecutor(max_workers=2) as pool:answers=list(pool.map(arrange,range(2)))
                assert sum(x['accepted'] for x in answers)==1,answers;c['concurrency']=answers;c['receipt_id']=next(x['id'] for x in answers if x['accepted'])
            else:c['receipt_id']=api(API+'/purchase/'+str(c['purchase_id'])+'/receipt',body)['data']
            save()
        if c.get('receipt_id'):c['receipt_sn']=get('receipt',c['receipt_id'])['noSn']
        if c.get('receipt_id') and key not in ['scheduled','concurrent'] and not c.get('ticket_id'):
            n=get('receipt',c['receipt_id']);c['ticket_id']=n['tickets'][0]['id'] if n['tickets'] else api(API+'/receipt/'+str(c['receipt_id'])+'/approve',{})['data'];save()
        if c.get('ticket_id') and key!='virtual':
            task=wait(lambda:api('/supplychain/wmsIntegration/tickets/'+str(c['ticket_id']))['data'],lambda x:x['dispatch_state'] in ['ACCEPTED','FAILED','CANCELED'],'Automatic warehouse dispatch did not settle')
            c['task']={k:task[k] for k in ['ticket_sn','external_order','external_owner','external_warehouse','connection_id']};c['ticket_sn']=task['ticket_sn'];save()
            if key=='dispatch_fail':assert task['dispatch_state']=='FAILED',task['dispatch_state']
            else:assert task['dispatch_state']=='ACCEPTED' or key=='late' and task['dispatch_state']=='CANCELED'
        elif c.get('ticket_id'):c['ticket_sn']=get('ticket',c['ticket_id'])['sn']
        msg='SCIT_'+stamp+'_'+key
        if key in ['full','replay']:callback(c,msg,5)
        elif key=='partial':callback(c,msg,4,complete=False)
        elif key=='split':callback(c,msg+'_1',2,complete=False);callback(c,msg+'_2',3)
        elif key in ['short','rearrange']:callback(c,msg,8)
        elif key in ['bad','bad_keep']:callback(c,msg,3,2)
        elif key=='over':callback(c,msg,7,expected='failure')
        elif key=='wrong':callback(c,msg,5,wrong=True,expected='failure')
        elif key=='corrected':callback(c,msg,5,wrong=True,expected='failure');callback(c,msg,5)
        elif key=='late':
            if not c.get('cutoff_id'):
                n=get('receipt',c['receipt_id']);c['cutoff_id']=api(API+'/receipt/'+str(c['receipt_id'])+'/cutoff',dict(reason='[验收] 未收货前停止本批',mode='WITHDRAW',cutoff_version=n['cutoffVersion']))['data']['id'];save()
            wait(lambda:get('receipt',c['receipt_id']),lambda x:x['noState'] in [5,6],'Cutoff automatic processing did not complete')
            callback(c,msg,2,complete=False,expected='failure')
        expected_good=10 if key=='virtual' else 5 if key in ['full','split','corrected','replay'] else 4 if key=='partial' else 8 if key in ['short','rearrange'] else 3 if key in ['bad','bad_keep'] else 0;expected_bad=2 if key in ['bad','bad_keep'] else 0
        if c.get('ticket_id') and expected_good+expected_bad:wait(lambda:get('ticket',c['ticket_id']),lambda t:t['numberPosted']==expected_good+expected_bad,'Automatic inventory posting did not complete')
        if key=='replay' and not c.get('replay_verified'):
            c['sent'].remove(msg);save();callback(c,msg,5);c['replay_verified']=True;save()
        if key in ['rearrange','bad_keep'] and not c.get('difference_resolved'):
            differences=api(API+'/differences?'+urllib.parse.urlencode(dict(kind='receipt',id=c['receipt_id'])))['data'];d=next(x for x in differences if x['difference_kind']==('SHORT' if key=='rearrange' else 'QUALITY'))
            body=dict(decision='REARRANGE' if key=='rearrange' else 'KEEP_BAD',reason='[验收] 已核对仓库原始回传及本次库存结果',control_version=d['control_version'])
            if key=='rearrange':
                def resolve(_):
                    try:return dict(accepted=True,result=api(API+'/differences/'+str(d['id']),body)['data'])
                    except Exception:return dict(accepted=False)
                with ThreadPoolExecutor(max_workers=2) as pool:answers=list(pool.map(resolve,range(2)))
                assert sum(x['accepted'] for x in answers)==1,answers;c['difference_concurrency']=answers;c['supplemental_receipt_id']=next(x['result']['receipt_id'] for x in answers if x['accepted'])
            else:api(API+'/differences/'+str(d['id']),body)
            c['difference_resolved']=True;save()
        c['final_purchase']=get('purchase',c['purchase_id']);c['final_receipt']=get('receipt',c['receipt_id']) if c.get('receipt_id') else None;c['final_ticket']=get('ticket',c['ticket_id']) if c.get('ticket_id') else None;c['stock']=balances(c['sku']);assert c['stock']==dict(good=expected_good,bad=expected_bad,good_available=expected_good,bad_available=expected_bad,locked=0),(key,c['stock'])
        c['inventory_layers']=inventory_layers(c['sku'],expected_good,expected_bad)
        if key in ['wrong','corrected']:
            c['wrong_stock']=balances(c['wrong_sku']);assert c['wrong_stock']['good']==c['wrong_stock']['bad']==0
        c['differences']=api(API+'/differences?'+urllib.parse.urlencode(dict(kind='purchase',id=c['purchase_id'])))['data']
        if key in ['short','rearrange','bad','bad_keep','over','wrong','corrected']:
            kind='SHORT' if key in ['short','rearrange'] else 'QUALITY' if key in ['bad','bad_keep'] else 'OVER' if key=='over' else 'WRONG_SKU'
            difference=next(x for x in c['differences'] if x['difference_kind']==kind)
            assert difference['state']==('RESOLVED' if key in ['rearrange','bad_keep'] else 'CORRECTED' if key=='corrected' else 'OPEN'),(key,difference)
            if key=='rearrange':assert get('receipt',c['supplemental_receipt_id'])['numberExpected']==2
        c['operations']=api(API+'/operations?'+urllib.parse.urlencode(dict(number=c['purchase_sn'])))['data'];c['url']=BASE+'/oms-supplychain/poInfo?purchaseId='+str(c['purchase_id']);c['result']='PASS';save();print('PASS',key,'stock',expected_good,expected_bad,flush=True)
    assert protected()==data['protected'],'Original business/inventory/queue/job baseline changed';data['protected_baseline_unchanged']=True;data['completed_at']=datetime.now().isoformat();save();print('PASS',len(data['cases']),'retained procurement scenarios; original records and inventory protected')


if __name__=='__main__':main()
