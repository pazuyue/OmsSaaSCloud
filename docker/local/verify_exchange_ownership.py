"""Fresh synthetic acceptance: one SO, immutable A-B-A-B shipment lineage.

Only local TEST warehouses and trusted platform simulation are used. Each stage
is retained so interrupted verification can resume without new stock receipts.
"""
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
import verify_allocation_rules_v2 as fixture

OUT=Path(__file__).resolve().parents[2]/'outputs/exchange-ownership'
ORDER='qm_oms_saas_order'
INVENTORY='qm_oms_saas_inventory'
SUPPLY='qm_oms_saas_commodity'
CHANNEL='qm_oms_saas_channel'

def save(path,value):
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')

class Case:
    def __init__(self,kind,token):
        self.path=OUT/(kind+'-case.json');self.token=token
        if self.path.exists():self.c=json.loads(self.path.read_text(encoding='utf-8'))
        else:
            stamp=datetime.now().strftime('%Y%m%d%H%M%S');tag='EXCH_'+kind.upper()+'_'+stamp;tid=('995' if kind=='after' else '994')+stamp
            self.c={'case_id':stamp,'created':datetime.now().strftime('%Y-%m-%d %H:%M:%S'),'sku':tag+'_A','target_sku':tag+'_B','channel_name':'[换货归属验收] '+kind+' '+stamp,'seller':tag,'platform_tid':tid,'platform_oid':tid+'01','warehouses':[{'code':tag+'_V','quantity':10,'package_count':1}],'claims':[]}
            self.save()
    def save(self):save(self.path,self.c)
    def api(self,path,body=None):return request(path,payload=body,token=self.token,method='POST' if body is not None else 'GET')
    def order(self):return self.api('/order/orders/business/'+str(self.c['order_id']))['data']
    def exchange(self,e):return self.api('/order/exchanges/'+str(e['id']))['data']
    def act(self,e,action,body=None):return self.api('/order/exchanges/'+str(e['id'])+'/'+action,{'control_version':self.exchange(e)['control_version'],**(body or {})})['data']
    def shipment(self,id):return self.api('/order/orders/shipments/'+str(id))['data']
    def prepare(self):
        fixture.MANIFEST=self.path
        if not self.c.get('prepared'):
            fixture.prepare_case(self.c,self.token);self.c['prepared']=True;self.save()
        if not self.c.get('target_ready'):
            c=self.c;sample=self.api('/goods/info/list?pageNum=1&pageSize=1',{})['rows'][0]
            if not sql("SELECT id FROM goods_sku_sn_info WHERE UPPER(company_code)='QM' AND sku_sn="+quote(c['target_sku']),SUPPLY):
                self.api('/goods/info',{'skuSn':c['target_sku'],'goodsSn':c['target_sku'],'goodsName':'[换货归属验收] B','categoryCode':sample['categoryCode'],'colorCode':sample['colorCode'],'sizeCode':sample['sizeCode'],'marketPrice':1,'validity':30})
            payload={'relationSn':'TEST-OWN-B-'+c['case_id'],'wmsInventoryBatch':{'companyCode':'QM','storeCode':c['warehouses'][0]['code'],'skuSn':c['target_sku'],'batchCode':'TEST-OWN-B-'+c['case_id'],'zpActualNumber':2,'zpAvailableNumber':2,'transactionPrice':2.5}}
            r=subprocess.run(['wsl.exe','--','docker','exec','-i','oms-local-inventory','curl','-fsS','-H','from-source: inner','-H','Content-Type: application/json','--data-binary','@-','http://localhost:9303/api/WmsInventory/addInventory?company_code=QM'],input=json.dumps(payload).encode(),capture_output=True,timeout=45)
            assert r.returncode==0 and json.loads(r.stdout)['code']==200,'Fixture receive failed'
            sql(f"INSERT IGNORE INTO oms_channel_inventory(company_code,channel_id,sku_sn,quota_stock,stock_scope_json) VALUES ('QM',{c['channel_id']},{quote(c['target_sku'])},10,{quote(json.dumps([c['warehouses'][0]['code']]))})",INVENTORY)
            c['target_ready']=True;self.save()
    def allocate(self):
        o=self.order()
        if o['review_status']!='APPROVED':
            r=self.api('/order/orders/business/approve-batch',{'orders':[{'id':o['id'],'control_version':o['control_version']}],'reason':'[验收] 原单换品后审核'})['data'];assert r[0]['success'],r;o=self.order()
        if o['allocation_status']!='FULL':
            r=self.api('/order/orders/business/allocate-batch',{'items':[{'id':o['id'],'control_version':o['control_version']}]})['data'];assert r[0]['success'],r
        return self.order()
    def dispatch_ship(self,s):
        s=self.shipment(s['id'])
        if s['carrier_match_status']!='MATCHED':
            r=self.api('/order/orders/shipments/match-carrier',{'items':[{'id':s['id'],'control_version':s['control_version']}]})['data'];assert r[0]['success'],r;s=self.shipment(s['id'])
        if s['wms_dispatch_state']=='NONE':
            r=self.api('/order/orders/shipments/dispatch',{'items':[{'id':s['id'],'control_version':s['control_version']}]})['data'];assert r[0]['success'],r
        s=self.shipment(s['id'])
        if s['wms_dispatch_state']=='PENDING':self.api('/order/orders/shipments/'+str(s['id'])+'/continue-dispatch',{'control_version':s['control_version']})
        s=wait_for(lambda:self.shipment(s['id']),lambda x:x['wms_dispatch_state']=='ACCEPTED','TEST WMS dispatch incomplete')
        assert s['warehouse_target']['environment']=='TEST'
        if s['shipped_quantity']!=s['quantity']:
            r=subprocess.run([sys.executable,str(Path(__file__).with_name('simulate_qimen_delivery.py')),s['shipment_sn']],capture_output=True,text=True,encoding='utf-8',timeout=120)
            assert r.returncode==0,'TEST outbound failed: '+r.stdout[-300:]+r.stderr[-300:]
        return wait_for(lambda:self.shipment(s['id']),lambda x:x['shipped_quantity']==x['quantity'],'TEST outbound posting incomplete')
    def authorize(self):
        if self.c.get('authorized'):return
        c=self.c;app=next(a for a in self.api('/channel/platform/apps')['data'] if int(a.get('simulation_mode',0))==1)
        self.api(f"/channel/platform/shops/{c['channel_id']}/binding",{'app_id':app['id'],'expected_shop_id':'700'+str(c['channel_id'])});self.api(f"/channel/platform/shops/{c['channel_id']}/simulate",{})
        capability=self.api('/order/exchanges/capability/'+str(c['channel_id']))['data'];assert capability['simulation'] and capability['can_query']
        self.api('/channel/exchangePlatform/'+str(c['channel_id'])+'/access',{'revision':capability['revision'],'read_enabled':1,'write_enabled':1})
        self.api('/order/exchanges/configs',{'channel_id':c['channel_id'],'revision':0,'initial_time':(datetime.now()-timedelta(minutes=5)).strftime('%Y-%m-%d %H:%M:%S'),'enabled':1,'write_enabled':1,'auto_convert':1})
        c['authorized']=True;self.save()
    def inject(self,index,quantity=2):
        c=self.c;dispute='999'+c['case_id']+str(index).zfill(2)
        payload={'dispute_id':dispute,'biz_order_id':c['platform_oid'],'num':quantity,'status':'1','good_status':'已发货','modified':datetime.now().strftime('%Y-%m-%d %H:%M:%S'),'time_out':(datetime.now()+timedelta(days=2)).strftime('%Y-%m-%d %H:%M:%S'),'bought_sku':'实际发货来源需人工核对','exchange_sku':'[验收] 换出规格','speed_exchange':0,'refund_phase':'yes'}
        sql(f"INSERT IGNORE INTO channel_exchange_sim(company_code,channel_id,dispute_id,payload,modified) VALUES ('qm',{c['channel_id']},{quote(dispute)},{quote(json.dumps(payload,ensure_ascii=False))},{quote(payload['modified'])})",CHANNEL)
        return wait_for(lambda:self.api('/order/exchanges?number='+dispute)['rows'],lambda rows:len(rows)==1,'Application sync incomplete',120)[0]
    def returned(self,e,sku):
        c=self.c;e=self.exchange(e);result=e['return_result'];assert result['dispatch_state']=='ACCEPTED',result.get('last_error')
        connection=next(x for x in self.api('/supplychain/wmsIntegration/connections')['data'] if x['id']==c['connection_id']);assert connection['environment']=='TEST' and connection['endpoint']=='http://wms-simulator:8080/qimen/gateway'
        credential=next(x for x in json.loads(Path(__file__).with_name('wms-simulator-credentials.json').read_text('utf-8')) if x['app_key']==connection['app_key'] and x['customer_id']==connection['customer_id'])
        root=ET.Element('request');head=ET.SubElement(root,'returnOrder')
        for k,v in {'returnOrderCode':e['return_sn'],'returnOrderId':result['external_order'],'warehouseCode':result['external_warehouse'],'ownerCode':result['external_owner'],'outBizCode':'TEST_RETURN_'+str(e['id']),'orderType':'HHRK','confirmType':'0'}.items():ET.SubElement(head,k).text=str(v)
        item=ET.SubElement(ET.SubElement(root,'orderLines'),'orderLine')
        for k,v in {'orderLineNo':'1','itemCode':sku,'ownerCode':result['external_owner'],'actualQty':2,'inventoryType':'ZP','batchCode':'TEST_RETURN_'+str(e['id'])}.items():ET.SubElement(item,k).text=str(v)
        body=ET.tostring(root,encoding='unicode');save(OUT/('return-'+str(e['id'])+'.json'),{'return_sn':e['return_sn'],'body':body})
        event={'customer':connection['customer_id'],'owner':result['external_owner'],'warehouse':result['external_warehouse'],'sn':e['return_sn'],'external':result['external_order'],'hash':hashlib.sha256(body.encode()).hexdigest()}
        script='''import json,sqlite3,datetime
p=json.loads(PAYLOAD)
key=(p['customer'],'return',p['owner'],p['warehouse'],p['sn'])
with sqlite3.connect('/data/documents.sqlite',timeout=10) as db:
 db.row_factory=sqlite3.Row;db.execute('BEGIN IMMEDIATE')
 row=db.execute('SELECT * FROM documents WHERE customer=? AND kind=? AND owner=? AND warehouse=? AND sn=?',key).fetchone()
 assert row and row['external_id']==p['external'] and row['quantity']==2 and row['status'] in ('ACCEPT','FULFILLED')
 old=db.execute("SELECT body_hash FROM events WHERE customer=? AND kind='return' AND sn=? AND action='RETURN_CONFIRM'",(p['customer'],p['sn'])).fetchall()
 assert not old or all(v['body_hash']==p['hash'] for v in old)
 if not old:
  db.execute("UPDATE documents SET status='FULFILLED' WHERE customer=? AND kind=? AND owner=? AND warehouse=? AND sn=?",key)
  db.execute("INSERT INTO events(customer,kind,sn,action,body_hash,created_at) VALUES (?,'return',?,'RETURN_CONFIRM',?,?)",(p['customer'],p['sn'],p['hash'],datetime.datetime.now(datetime.timezone.utc).isoformat()))
'''.replace('PAYLOAD',repr(json.dumps(event)))
        r=subprocess.run(['wsl.exe','--','docker','exec','-i','oms-local-wms-simulator','python','-'],input=script.encode(),capture_output=True,timeout=30);assert r.returncode==0,'TEST return identity mismatch'
        def callback():
            p={'app_key':connection['app_key'],'customerId':connection['customer_id'],'method':'taobao.qimen.returnorder.confirm','v':'2.0','format':'xml','sign_method':'md5','timestamp':datetime.now().strftime('%Y-%m-%d %H:%M:%S')};p['sign']=sign(p,body,credential['secret'])
            req=urllib.request.Request(BASE+'/prod-api/supplychain/wmsCallback/qm/'+connection['callback_key']+'?'+urllib.parse.urlencode(p),data=body.encode(),headers={'Content-Type':'application/xml;charset=UTF-8'},method='POST')
            with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req,timeout=40) as response:reply=ET.fromstring(response.read())
            assert reply.findtext('flag')=='success',reply.findtext('message')
        callback();snapshot=sql("SELECT total_stock,available_stock,reserved_stock,version FROM oms_inventory WHERE company_code='QM' AND sku_sn="+quote(sku),INVENTORY);callback();assert snapshot==sql("SELECT total_stock,available_stock,reserved_stock,version FROM oms_inventory WHERE company_code='QM' AND sku_sn="+quote(sku),INVENTORY)
    def after(self):
        c=self.c;self.prepare();self.authorize()
        source=self.dispatch_ship(self.allocate()['shipments'][0]);c['original_shipment_id']=source['id'];self.save()
        for index,target in enumerate([c['target_sku'],c['sku'],c['target_sku']],1):
            if len(c['claims'])<index:
                e=self.inject(index);c['claims'].append({'id':e['id'],'target':target,'source_shipment_id':source['id'],'source_shipment_line_id':source['lines'][0]['id']});self.save()
            record=c['claims'][index-1];e=self.exchange(record);source=self.shipment(record['source_shipment_id']);source_line=source['lines'][0]
            if not e.get('approved_version'):
                self.act(e,'review',{'target_sku':target,'source_shipment_line_id':source_line['id'],'return_store':c['warehouses'][0]['code'],'platform_address_id':'991','reason':'[验收] 精确来源 A→B→A→B','recipient_confirmed':True,'replacement_recipient':e['replacement_recipient']})
            e=self.exchange(e)
            if e['platform_status']=='1' and e['platform_operation']=='NONE':self.act(e,'execute',{'operation':'AGREE','reason':'[验收] 同意模拟申请'})
            e=wait_for(lambda:self.exchange(e),lambda x:x['platform_status'] in ('2','3','12','13'),'Agreement incomplete')
            if not e['return_sn']:self.act(e,'advance')
            e=wait_for(lambda:self.exchange(e),lambda x:bool(x['return_sn']),'Return not prepared');self.returned(e,source_line['sku_sn']);e=self.exchange(e)
            if e['platform_status']=='2':
                modified=max(datetime.now()+timedelta(seconds=2),datetime.strptime(e['source_modified'],'%Y-%m-%d %H:%M:%S')+timedelta(seconds=1)).strftime('%Y-%m-%d %H:%M:%S')
                sql(f"UPDATE channel_exchange_sim SET payload=JSON_SET(payload,'$.status','3','$.modified',{quote(modified)}),modified={quote(modified)} WHERE company_code='qm' AND channel_id={c['channel_id']} AND dispute_id={quote(e['dispute_id'])}",CHANNEL);self.act(e,'refresh')
            if self.exchange(e)['return_qc']!='PASSED':self.act(e,'qc',{'reason':'[验收] 实收来源与数量一致'})
            e=self.exchange(e)
            if e['platform_status']=='3' and e['platform_operation']=='NONE':self.act(e,'execute',{'operation':'RECEIVED','reason':'[验收] 实际退回库存已入账'})
            e=wait_for(lambda:self.exchange(e),lambda x:x['platform_status'] in ('12','13'),'Receipt confirmation incomplete')
            if not e.get('fulfillment_line_id'):self.act(e,'advance')
            e=wait_for(lambda:self.exchange(e),lambda x:bool(x.get('fulfillment_line_id')),'Replacement line missing');assert e['fulfillment_order_id']==c['order_id']
            if not e['shipments']:self.act(e,'allocate')
            e=self.exchange(e);assert len(e['shipments'])==1;source=self.dispatch_ship(e['shipments'][0]);assert source['business_type']=='EXCHANGE' and source['business_owner_sn']==e['exchange_sn'] and source['lines'][0]['sku_sn']==target
            e=self.exchange(e)
            if e['platform_status']=='12' and e['platform_operation']=='NONE':self.act(e,'execute',{'operation':'CONSIGN','reason':'[验收] 换出实际出库与运单已确认'})
            e=wait_for(lambda:self.exchange(e),lambda x:x['state']=='COMPLETED','Consignment incomplete')
            record.update(exchange_sn=e['exchange_sn'],shipment_id=source['id'],shipment_sn=source['shipment_sn'],shipment_line_id=source['lines'][0]['id'],fulfillment_line_id=e['fulfillment_line_id'],return_sn=e['return_sn'],completed=True);self.save()
            logs=self.api('/supplychain/wmsIntegration/log-center?numberType=EXCHANGE&number='+e['exchange_sn']+'&allTime=true')['data']['rows'];assert {'EXCHANGE_IN','EXCHANGE_OUT'}<=set(x['business_type'] for x in logs)
        a1=self.shipment(c['original_shipment_id'])['lines'][0];a2=self.shipment(c['claims'][1]['shipment_id'])['lines'][0]
        assert a1['id']!=a2['id'] and a1['order_line_id']!=a2['order_line_id'] and a1['sku_sn']==a2['sku_sn']==c['sku']
        o=self.order();assert o['shipped_quantity']==10 and len(o['shipments'])==1 and len(o['after_sales_shipments'])==3 and len(o['lines'])==1
        assert sql(f"SELECT COUNT(*) FROM oms_order WHERE company_code='QM' AND channel_id={c['channel_id']} AND platform_tid={quote(c['platform_tid'])}",ORDER)=='1'
        assert sql(f"SELECT COUNT(*) FROM oms_platform_order WHERE company_code='QM' AND channel_id={c['channel_id']} AND source_kind='EXCHANGE_PLAN'",ORDER)=='0'
        for claim in c['claims']:
            tickets=sql(f"SELECT business_type,original_sn,root_order_sn FROM wms_tickets WHERE company_code='QM' AND exchange_id={claim['id']} ORDER BY business_type",SUPPLY).splitlines()
            assert set(tickets)=={'EXCHANGE_IN\t'+claim['exchange_sn']+'\t'+c['order_sn'],'EXCHANGE_OUT\t'+claim['exchange_sn']+'\t'+c['order_sn']},tickets
        if not c.get('pending_id'):c['pending_id']=self.inject(9,9)['id'];self.save()
        pending=self.exchange({'id':c['pending_id']});sources={s['source_shipment_line_id']:s for s in pending['original_shipments']};assert sources[a1['id']]['claimable_quantity']==8 and sources[a2['id']]['claimable_quantity']==0
        try:
            rejection=self.api('/order/exchanges/'+str(pending['id'])+'/review',{'control_version':pending['control_version'],'target_sku':c['target_sku'],'source_shipment_line_id':a1['id'],'return_store':c['warehouses'][0]['code'],'platform_address_id':'991','reason':'[验收] 9 大于原 A 剩余 8','recipient_confirmed':True,'replacement_recipient':pending['replacement_recipient']})
        except AssertionError as error:
            assert '数量' in str(error) or '可' in str(error),str(error)
        else:raise AssertionError('Overclaim review unexpectedly approved')
        c['completed']=True;self.save();return {'order_id':o['id'],'order_sn':o['order_sn'],'one_so':True,'sale_outbound':10,'exchanges':c['claims'],'A_source_identity_distinct':True,'overclaim_rejected':True}
    def before(self):
        c=self.c;self.prepare();self.allocate()
        for index,target in enumerate([c['target_sku'],c['sku']],1):
            if len(c['claims'])<index:
                line=next(l for l in self.order()['lines'] if l['quantity']>l['cancelled_quantity'] and l['sku_sn']==(c['sku'] if index==1 else c['target_sku']))
                e=self.api('/order/exchanges',{'order_id':c['order_id'],'order_line_id':line['id'],'platform_oid':c['platform_oid'],'quantity':2,'target_sku':target,'reason':'[验收] 原单未发货 A→B→A','request_key':'OWN_INTERNAL_'+c['case_id']+'_'+str(index)})['data'];c['claims'].append({'id':e['id'],'source_line_id':line['id']});self.save()
            record=c['claims'][index-1];e=self.exchange(record)
            if not e.get('approved_version'):self.act(e,'review',{'target_sku':target,'reason':'[验收] 已确认协商换品'})
            for _ in range(8):
                if self.exchange(e).get('fulfillment_line_id'):break
                self.act(e,'advance')
            e=self.exchange(e);assert e.get('fulfillment_line_id') and e['fulfillment_order_id']==c['order_id'];record.update(exchange_sn=e['exchange_sn'],fulfillment_line_id=e['fulfillment_line_id']);self.save();self.allocate()
        o=self.order();a=[l for l in o['lines'] if l['sku_sn']==c['sku']];assert len(a)==2 and a[0]['id']!=a[1]['id'];assert sorted(l['quantity']-l['cancelled_quantity'] for l in a)==[2,8]
        assert o['shipped_quantity']==0 and o['reserved_quantity']==10 and o['allocation_quantity']==10
        active=[s for s in o['shipments'] if s['status']!='VOIDED'];assert len(active)==2 and sorted(s['quantity'] for s in active)==[2,8] and all(s['wms_dispatch_state']=='NONE' for s in active)
        assert sql(f"SELECT COUNT(*) FROM oms_order WHERE company_code='QM' AND channel_id={c['channel_id']} AND platform_tid={quote(c['platform_tid'])}",ORDER)=='1'
        c['completed']=True;self.save();return {'order_id':o['id'],'order_sn':o['order_sn'],'one_so':True,'effective_A_quantities':[8,2],'warehouse_sent':False,'exchanges':c['claims']}

def main():
    OUT.mkdir(parents=True,exist_ok=True);token=login();original_api=fixture.api
    def fixture_api(path,credential,payload=None,post=False):
        if path=='/inventory/api/WmsInventory/addInventory?company_code=QM':payload['wmsInventoryBatch']['transactionPrice']=2.5
        return original_api(path,credential,payload,post)
    fixture.api=fixture_api
    try:
        after=Case('after',token).after();save(OUT/'after-verification.json',after);print(json.dumps(after,ensure_ascii=True),flush=True)
        before=Case('before',token).before();save(OUT/'before-verification.json',before);print(json.dumps(before,ensure_ascii=True),flush=True)
    finally:request('/auth/logout',token=token,method='DELETE')

if __name__=='__main__':main()
