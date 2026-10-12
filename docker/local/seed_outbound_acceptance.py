"""Create and retain one explicitly requested acceptance order using an isolated test SKU/shop.
No push database access, WMS dispatch or platform shipment notification.
"""
import hashlib
import json
import subprocess
import time
import uuid
from datetime import datetime
from local_db import ROOT, execute, quote
from verify_business import request

ORDER='qm_oms_saas_order'
STOCK='qm_oms_saas_inventory'
GOODS='qm_oms_saas_commodity'
CHANNEL='qm_oms_saas_channel'
OUTPUT=ROOT/'outputs/outbound-acceptance'
MANIFEST=OUTPUT/'retained-test.json'


def internal(path,body,token):
    result=subprocess.run(['docker','exec','-i','oms-local-inventory','curl','-sS','--max-time','40','-H','Content-Type: application/json','-H','from-source: inner','-H','Authorization: Bearer '+token,'--data-binary','@-','http://localhost:9303'+path],input=json.dumps(body,ensure_ascii=False).encode('utf-8'),stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    if result.returncode:raise RuntimeError('Local acceptance inventory request did not finish')
    reply=json.loads(result.stdout)
    if reply.get('code')!=200:raise RuntimeError('Acceptance inventory request: '+str(reply.get('msg')))
    return reply.get('data')


def main():
    OUTPUT.mkdir(parents=True,exist_ok=True)
    case=json.loads(MANIFEST.read_text()) if MANIFEST.exists() else {'case_id':datetime.now().strftime('%Y%m%d%H%M%S'),'company':'QM','stage':'INIT'}
    def save():MANIFEST.write_text(json.dumps(case,ensure_ascii=False,indent=2),encoding='utf-8')
    captcha=request('/code');answer=subprocess.check_output(['docker','exec','oms-local-redis','redis-cli','--raw','GET','captcha_codes:'+captcha['uuid']],text=True).strip().strip('"')
    token=request('/auth/login',{'username':'admin','password':'admin123','uuid':captcha['uuid'],'code':answer})['data']['access_token']
    try:
        if 'channel_id' not in case:
            case.update(channel_name='[验收测试] 出库与运单 '+case['case_id'],sku='ACCEPT_OUT_'+case['case_id'],platform_tid='990'+case['case_id'],order_sn='SO'+uuid.uuid4().hex)
            request('/channel/channel',{'channelName':case['channel_name'],'channelType':'TM','enabled':1,'toChannelEnabled':1,'syncEnabled':0,'mmodelType':1},token,'POST')
            case['channel_id']=int(execute('SELECT channel_id FROM t_channel WHERE channel_name='+quote(case['channel_name']),CHANNEL).strip());save()
        channel=case['channel_id'];sku=case['sku'];tid=case['platform_tid']
        assert sku.startswith('ACCEPT_OUT_') and tid.startswith('990')
        assert execute('SELECT channel_name FROM t_channel WHERE channel_id='+str(channel),CHANNEL).strip()==case['channel_name']
        if case['stage']=='INIT':
            sample=request('/goods/info/list?pageNum=1&pageSize=1',{},token)['rows'][0]
            if execute('SELECT COUNT(*) FROM goods_sku_sn_info WHERE sku_sn='+quote(sku),GOODS).strip()=='0':
                request('/goods/info',{'skuSn':sku,'goodsSn':'ACCEPT_OUT_'+case['case_id'],'goodsName':'[验收测试] 出库包裹商品','categoryCode':sample['categoryCode'],'colorCode':sample['colorCode'],'sizeCode':sample['sizeCode'],'marketPrice':1,'validity':30},token,'POST')
            now=datetime.now().strftime('%Y-%m-%d %H:%M:%S')
            configs=request('/order/orders/configs',token=token)['data']
            if not any(c['channel_id']==channel for c in configs):
                request('/order/orders/configs',{'channel_id':channel,'source_key':'sysinfo113','seller_nick':'ACCEPT_'+case['case_id'],'enabled':0,'auto_convert':0,'review_mode':'AUTO','manual_match_review':1,'allowed_trade_types':['fixed'],'allow_public_stock':1,'interval_seconds':30,'initial_time':now},token,'POST')
            catalog=request('/order/orders/carrier-catalog',token=token)['data']
            carriers=catalog['carriers']
            candidates=[]
            for warehouse in catalog['warehouses']:
                if int(warehouse.get('outbound_mode') or 0)!=2:continue
                for carrier in carriers:
                    if carrier['enabled']!=1:continue
                    for service in carrier['services']:
                        if service['owner_code']==warehouse['owner_code'] and service['real_store_code']==warehouse['real_store_code'] and '北京' not in service['excluded_provinces'] and (not service['provinces'] or '北京' in service['provinces']):candidates.append((warehouse,carrier))
            assert candidates,'Need an existing active virtual warehouse and configured Beijing carrier'
            warehouse,carrier=candidates[0];case.update(store_code=warehouse['wms_simulation_code'],owner_code=warehouse['owner_code'],real_store_code=warehouse['real_store_code'],carrier_code=carrier['carrier_code']);save()
            config=request('/order/orders/allocation-config/'+str(channel),token=token)['data']
            request('/order/orders/allocation-config',{'channel_id':channel,'revision':config['revision'],'policy':{'enabled':1,'owner_code':case['owner_code'],'warehouse_order':[case['store_code']],'province_priorities':{},'allow_multi':0,'allow_partial':1,'allow_split_sku':0}},token,'POST')
            config=next(c for c in request('/order/orders/carrier-configs',token=token)['data'] if c['channel_id']==channel)
            request('/order/orders/carrier-configs',{'channel_id':channel,'revision':config['revision'],'policy':{'enabled':1,'carrier_order':[case['carrier_code']],'routes':[]}},token,'POST')
            internal('/api/WmsInventory/addInventory?company_code=QM',{'relationSn':'TEST-IN-'+case['case_id'],'wmsInventoryBatch':{'companyCode':'QM','storeCode':case['store_code'],'skuSn':sku,'batchCode':'TEST-BATCH-'+case['case_id'],'zpActualNumber':12,'zpAvailableNumber':12,'zpLockNumber':0,'cpActualNumber':0,'cpAvailableNumber':0,'cpLockNumber':0}},token)
            execute(f"INSERT INTO oms_channel_inventory(company_code,channel_id,sku_sn,quota_stock,stock_scope_json) VALUES ('QM',{channel},{quote(sku)},12,{quote(json.dumps([case['store_code']]))}) ON DUPLICATE KEY UPDATE quota_stock=12,stock_scope_json=VALUES(stock_scope_json)",STOCK)
            demand={'channelId':channel,'platformTid':tid,'allowPublicStock':True,'lines':[{'platformOid':tid+'01','skuSn':sku,'quantity':8}]}
            internal('/orderStock/internal/register?company_code=QM',demand,token);reserved=internal('/orderStock/internal/reserve?company_code=QM',demand,token);assert reserved[0]['reserved_quantity']==8
            trade={'tid':tid,'status':'WAIT_SELLER_SEND_GOODS','type':'fixed','seller_nick':'ACCEPT_'+case['case_id'],'payment':'8.00','receiver_name':'验收收件人','receiver_mobile':'TEST-MASKED','receiver_state':'北京市','receiver_city':'北京市','receiver_district':'测试区','receiver_address':'验收测试地址，无实际配送','orders':{'order':[{'oid':tid+'01','outer_sku_id':sku,'num':8,'payment':'8.00','title':'[验收测试] 出库包裹商品'}]}}
            payload=json.dumps({'trade_fullinfo_get_response':{'trade':trade}},ensure_ascii=False);digest=hashlib.sha256(payload.encode()).hexdigest()
            if execute('SELECT COUNT(*) FROM oms_order WHERE company_code=\'QM\' AND order_sn='+quote(case['order_sn']),ORDER).strip()=='0':
                execute(f"""START TRANSACTION;
INSERT INTO oms_platform_order(company_code,channel_id,platform_tid,platform_status,trade_type,seller_nick,platform_created,source_modified,payload_hash,raw_payload,processing_payload,processing_hash,conversion_status,demand_registered,payment,receiver_name,receiver_phone,receiver_address) VALUES ('QM',{channel},{quote(tid)},'WAIT_SELLER_SEND_GOODS','fixed',{quote('ACCEPT_'+case['case_id'])},NOW(),NOW(),{quote(digest)},{quote(payload)},{quote(payload)},{quote(digest)},'SUCCESS',1,8,'验收收件人','TEST-MASKED','验收测试地址，无实际配送');
SET @acceptance_platform_id=LAST_INSERT_ID();
INSERT INTO oms_platform_order_line(company_code,platform_order_id,platform_oid,outer_sku_id,title,quantity,payment,sku_sn) VALUES ('QM',@acceptance_platform_id,{quote(tid+'01')},{quote(sku)},'[验收测试] 出库包裹商品',8,8,{quote(sku)});
INSERT INTO oms_order(company_code,order_sn,channel_id,platform_order_id,platform_tid,owner_code,inventory_status,payment,receiver_name,receiver_phone,receiver_address,review_status,review_policy,review_by,review_at,review_reason,reserved_at) VALUES ('QM',{quote(case['order_sn'])},{channel},@acceptance_platform_id,{quote(tid)},{quote(case['owner_code'])},'RESERVED',8,'验收收件人','TEST-MASKED','验收测试地址，无实际配送','APPROVED','AUTO','验收初始化',NOW(),'验收测试单据，允许部分发货',NOW());
SET @acceptance_order_id=LAST_INSERT_ID();
UPDATE oms_platform_order SET business_order_id=@acceptance_order_id WHERE id=@acceptance_platform_id;
INSERT INTO oms_order_line(company_code,order_id,platform_oid,sku_sn,goods_name,quantity,payment,reserved_quantity) VALUES ('QM',@acceptance_order_id,{quote(tid+'01')},{quote(sku)},'[验收测试] 出库包裹商品',8,8,8);
INSERT INTO oms_order_process_log(company_code,platform_order_id,action,result,message,operator_name) VALUES ('QM',@acceptance_platform_id,'ACCEPTANCE_SEED','SUCCESS','用户要求保留的验收测试单；独立SKU、暂停拉单，无平台发货同步','验收初始化');
COMMIT;""",ORDER)
            case['order_id']=int(execute('SELECT id FROM oms_order WHERE company_code=\'QM\' AND order_sn='+quote(case['order_sn']),ORDER).strip());case['stage']='SEEDED';save()
        order=request('/order/orders/business/'+str(case['order_id']),token=token)['data'];assert order['order_sn']==case['order_sn'] and order['channel_id']==channel
        if not order['shipments']:
            result=request('/order/orders/business/allocate-batch',{'items':[{'id':order['id'],'control_version':order['control_version']}]},token,'POST')['data'];assert result[0]['success'],result
            order=request('/order/orders/business/'+str(case['order_id']),token=token)['data']
        assert len(order['shipments'])==1
        case['shipment_id']=order['shipments'][0]['id'];case['shipment_sn']=order['shipments'][0]['shipment_sn'];save()
        def shipment():return request('/order/orders/shipments/'+str(case['shipment_id']),token=token)['data']
        for target,tag in [(4,'A'),(6,'B')]:
            current=shipment();assert current['order_id']==case['order_id'] and all(line['sku_sn']==sku for line in current['lines'])
            if current['shipped_quantity']<target:
                body={'items':[{'id':current['id'],'control_version':current['control_version'],'lines':[{'id':current['lines'][0]['id'],'quantity':target-current['shipped_quantity']}]}]}
                preview=request('/order/orders/shipments/virtual-outbound-preview',body,token,'POST')['data'];assert preview[0]['success'],preview
                posted=request('/order/orders/shipments/virtual-outbound',body,token,'POST')['data'];assert posted[0]['success'],posted
                until=time.monotonic()+75
                while shipment()['shipped_quantity']<target:
                    if time.monotonic()>until:raise RuntimeError('Retained acceptance outbound is still pending; inspect the original task')
                    time.sleep(1)
                current=shipment()
            express='TEST-ACCEPT-'+case['case_id']+'-'+tag
            if not any(p['express_code']==express for p in current.get('packages',[])):
                result=request('/order/orders/shipments/'+str(current['id'])+'/packages',{'control_version':current['control_version'],'request_id':'PK_ACCEPT_'+case['case_id']+'_'+tag,'express_code':express,'package_code':'TEST-PACK-'+case['case_id']+'-'+tag,'lines':[{'id':current['lines'][0]['id'],'quantity':4 if tag=='A' else 2}]},token,'POST')['data'];assert result['success'],result
        current=shipment();assert current['shipped_quantity']>=6 and len(current['packages'])>=2
        case.update(stage='RETAINED',quantity=8,shipped_quantity=current['shipped_quantity'],remaining_quantity=8-current['shipped_quantity'],package_count=len(current['packages']),business_url='http://127.0.0.1:8088/oms-order/business?view=business&orderId='+str(case['order_id']),shipment_url='http://127.0.0.1:8088/oms-order/shipments?number='+case['shipment_sn']);save()
        print(json.dumps({k:case[k] for k in ['order_sn','shipment_sn','sku','shipped_quantity','remaining_quantity','package_count','business_url']},ensure_ascii=False))
    finally:request('/auth/logout',token=token,method='DELETE')


if __name__=='__main__':main()
