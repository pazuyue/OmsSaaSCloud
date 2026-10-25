"""Retained end-to-end acceptance of ten requested automatic refund scenarios.

Only this manifest's new shops/SKUs/orders are mutated. No refund review, return
creation, warehouse return dispatch, or payment is performed on a target refund
by the runner. Prior completed money-only refunds are explicitly labelled setup.
Physical stock uses receive APIs and signed local TEST warehouse callbacks.
"""
import hashlib
import json
import subprocess
import sys
import time
import urllib.parse
from datetime import datetime, timedelta
from pathlib import Path

from verify_goods_workspace_ui import BASE, login, request
from test_multi_virtual_shipping import sql, quote
import verify_allocation_rules_v2 as fixture
import verify_exchange_ownership as ownership

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'outputs/after-ship-refund-auto'
ORDER, INV, GOODS, CHANNEL = 'qm_oms_saas_order', 'qm_oms_saas_inventory', 'qm_oms_saas_commodity', 'qm_oms_saas_channel'
SOURCE = 'refund_acceptance_source'
TITLES = {'01': '小额10元免退货', '02A': '小额20元边界', '02B': '小额20.01元超限', '03': '先退10再退15累计超限', '04': '仓库已接单未出库截单', '05': '截单竞争期间实际出库', '06': '在途仅退款物流拦截', '07': '已签收两件仅退款实际退货', '08': '三件商品已发一件未发两件', '09': '先退差价20再退剩余80含赠品', '10': '先退差价20再申请100超额'}


def dump(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str) + '\n', 'utf-8')


class Run:
    def __init__(self):
        OUT.mkdir(parents=True, exist_ok=True)
        self.path = OUT / 'manifest.json'
        self.data = json.loads(self.path.read_text('utf-8')) if self.path.exists() else {'run_id': datetime.now().strftime('%Y%m%d%H%M%S'), 'cases': {}, 'target_rules': {'small_money_cumulative_limit': 20, 'auto_payment_limit': 500}, 'not_implemented': ['小额免退货自动审批及累计阈值', '实际出库后自动确认取消购买和货物方案', '仅退款自动创建退回安排']}
        self.save()
        self.token = None

    def save(self): dump(self.path, self.data)

    def api(self, path, body=None, method=None):
        r = request(path, payload=body, token=self.token, method=method or ('POST' if body is not None else 'GET'))
        assert r.get('code', 200) == 200, (path, r.get('msg'))
        return r.get('data', r)

    def ready(self):
        until = time.monotonic() + 360
        while True:
            try:
                self.token = login()
                self.api('/goods/info/list?pageNum=1&pageSize=1', {})
                self.api('/order/refunds/catalog')
                self.api('/supplychain/wmsIntegration/connections')
                print('Local order, goods, inventory and warehouse APIs ready', flush=True)
                return
            except Exception:
                if time.monotonic() >= until: raise RuntimeError('Local APIs have not become ready; retained manifest can resume') from None
                time.sleep(3)

    def protect(self):
        if 'protected' in self.data: return
        from verify_merged_composite_gift_refunds import Acceptance
        prior = Acceptance()
        self.data['protected'] = {'merged_stock': prior.snapshot(), 'preceding_stock': prior.protected_order(), 'maximum_existing_channel': int(sql('SELECT MAX(channel_id) FROM t_channel', CHANNEL)), 'queues': sql('SELECT business_type,desired_mode,concurrency FROM sys_mq_queue_policy ORDER BY business_type', 'ry-cloud'), 'jobs': sql('SELECT job_id,status,cron_expression FROM sys_job ORDER BY job_id', 'ry-cloud')}
        self.data['protected']['old_settings_hash'] = self.settings_hash()
        self.save()

    def settings_hash(self):
        limit = self.data['protected']['maximum_existing_channel']
        values = [sql(f'SELECT * FROM {table} WHERE channel_id<={limit} ORDER BY company_code,channel_id', ORDER) for table in ['oms_refund_flow_policy', 'oms_refund_parcel_config']]
        values.append(sql(f'SELECT company_code,channel_id,enabled,auto_convert,revision FROM oms_refund_config WHERE channel_id<={limit} ORDER BY company_code,channel_id', ORDER))
        return hashlib.sha256(json.dumps(values).encode()).hexdigest()

    def snapshot(self, c):
        skus = [c['sku']] + ([c['gift']] if c.get('gift') else [])
        selected = ','.join(map(quote, skus))
        def rows(q, db=INV):
            raw = sql(q, db)
            return [x.split('\t') for x in raw.splitlines()] if raw else []
        return {'company_columns': ['sku','total','available','reserved','allocated'], 'company': rows('SELECT sku_sn,total_stock,available_stock,reserved_stock,allocated_stock FROM oms_inventory WHERE company_code=\'QM\' AND sku_sn IN ('+selected+') ORDER BY sku_sn'), 'warehouse_columns': ['store','sku','actual','available','locked'], 'warehouse': rows('SELECT store_code,sku_sn,zp_actual_number,zp_available_number,zp_lock_number FROM wms_inventory WHERE company_code=\'QM\' AND sku_sn IN ('+selected+') ORDER BY store_code,sku_sn'), 'channel_columns':['sku','available','reserved','allocated','quota','quota_reserved'], 'channel':rows(f'SELECT sku_sn,available_stock,reserved_stock,allocated_stock,quota_stock,quota_reserved_stock FROM oms_channel_inventory WHERE company_code=\'QM\' AND channel_id={c["channel_id"]} AND sku_sn IN ('+selected+') ORDER BY sku_sn')}

    def inner_receive(self, c, sku, store, qty):
        body = {'relationSn': 'AFIN_'+sku+'_'+store, 'wmsInventoryBatch': {'companyCode':'QM','storeCode':store,'skuSn':sku,'batchCode':'AFB_'+sku+'_'+store,'zpActualNumber':qty,'zpAvailableNumber':qty,'zpLockNumber':0,'cpActualNumber':0,'cpAvailableNumber':0,'cpLockNumber':0,'transactionPrice':10}}
        r = subprocess.run(['wsl.exe','--exec','docker','exec','-i','oms-local-inventory','curl','-fsS','-H','from-source: inner','-H','Content-Type: application/json','--data-binary','@-','http://localhost:9303/api/WmsInventory/addInventory?company_code=QM'],input=json.dumps(body).encode(),capture_output=True,timeout=60)
        assert r.returncode == 0 and json.loads(r.stdout)['code']==200, 'TEST receive API failed'

    def callback_setup(self, c, token):
        # prepare_case creates only master data before invoking this callback.
        scope = quote(json.dumps([w['code'] for w in c['warehouses']]))
        if not c.get('stock_ready'):
            for w in c['warehouses']:
                self.inner_receive(c,c['sku'],w['code'],w['quantity'])
            scope = quote(json.dumps([w['code'] for w in c['warehouses']]))
            sql(f"INSERT IGNORE INTO oms_channel_inventory(company_code,channel_id,sku_sn,quota_stock,stock_scope_json) VALUES ('QM',{c['channel_id']},{quote(c['sku'])},100,{scope})", INV)
            c['stock_ready']=True;self.save()
        if c['key']=='09' and not c.get('gift_ready'):
            c['gift']=c['sku'][:-1]+'G';self.save()
            sample=self.api('/goods/info/list?pageNum=1&pageSize=1',{})['rows'][0]
            existing=self.api('/goods/info/list?pageNum=1&pageSize=1',{'skuSn':c['gift']})['rows']
            if not existing:self.api('/goods/info',{'skuSn':c['gift'],'goodsSn':c['gift'],'goodsName':'[自动仅退款验收09] 关联赠品G','categoryCode':sample['categoryCode'],'colorCode':sample['colorCode'],'sizeCode':sample['sizeCode'],'marketPrice':10,'validity':30,'productType':'NORMAL','isGift':1})
            self.inner_receive(c,c['gift'],c['warehouses'][0]['code'],100)
            sql(f"INSERT IGNORE INTO oms_channel_inventory(company_code,channel_id,sku_sn,quota_stock,stock_scope_json) VALUES ('QM',{c['channel_id']},{quote(c['gift'])},100,{scope})", INV)
            if not c.get('gift_group'):
                c['gift_group']=self.api('/order/gifts/groups',{'name':'[自动仅退款验收09] 买A赠G '+c['case_id'],'enabled':1,'policy':{'start_time':(datetime.now()-timedelta(days=1)).strftime('%Y-%m-%d %H:%M:%S'),'end_time':(datetime.now()+timedelta(days=30)).strftime('%Y-%m-%d %H:%M:%S'),'shipped_policy':'RETURN','allow_waive':True,'difference_affects_amount':False,'rules':[{'key':'AFG','name':'买A一件赠G一件','type':'PRODUCT','minimum':1,'cap':1,'max_count':1,'skus':[c['sku']],'amount':'0','gifts':[{'sku_sn':c['gift'],'goods_name':'验收赠品G','quantity':1}],'tiers':[],'stop':True}]}})['id'];self.save()
            if not c.get('gift_scope'):
                c['gift_scope']=self.api('/order/gifts/shop-groups',{'name':'[自动仅退款验收09] 独立店铺组 '+c['case_id'],'enabled':1,'channels':[c['channel_id']],'links':[{'group_id':c['gift_group'],'continue_after_match':False}]})['id'];self.save()
            c['gift_ready']=True;self.save()
        if not c.get('initial_stock'):
            c['initial_stock']=self.snapshot(c);self.save()
        # A source fixture, never a direct business-order or stock mutation.
        if not sql(f"SELECT id FROM oms_platform_order WHERE company_code='QM' AND channel_id={c['channel_id']} AND platform_tid={quote(c['platform_tid'])}",ORDER):
            amount=str(c['quantity']*100)+'.00'
            trade={'tid':c['platform_tid'],'status':'WAIT_SELLER_SEND_GOODS','type':'fixed','seller_nick':c['seller'],'created':c['created'],'modified':c['created'],'pay_time':c['created'],'payment':amount,'post_fee':'0.00','discount_fee':'0.00','receiver_name':'自动仅退款验收 '+c['key'],'receiver_mobile':'TEST-MASKED','receiver_state':'北京市','receiver_city':'北京市','receiver_district':'验收区','receiver_address':'本地测试地址，无实际配送','orders':{'order':[{'oid':c['platform_oid'],'outer_sku_id':c['sku'],'num':c['quantity'],'payment':amount,'divide_order_fee':amount,'price':'100.00','discount_fee':'0.00','adjust_fee':'0.00','title':'[自动仅退款验收'+c['key']+'] '+c['title']}]}}
            raw=json.dumps({'trade_fullinfo_get_response':{'trade':trade}},ensure_ascii=False)
            values=[c['platform_tid'],'WAIT_SELLER_SEND_GOODS','fixed',c['seller'],c['created'],c['created'],c['created'],hashlib.sha256(raw.encode()).hexdigest(),raw,'WAITING']
            sql("INSERT INTO oms_platform_order(company_code,channel_id,platform_tid,platform_status,trade_type,seller_nick,platform_created,platform_modified,source_modified,payload_hash,raw_payload,conversion_status) VALUES ('QM',"+str(c['channel_id'])+','+','.join(quote(x) for x in values)+')',ORDER)

    def prepare(self, key):
        if key not in self.data['cases']:
            stamp=self.data['run_id'];tag='AF'+stamp+'C'+key
            qty=2 if key=='07' else 3 if key=='08' else 1
            self.data['cases'][key]={'key':key,'title':TITLES[key],'case_id':stamp+'_'+key,'created':datetime.now().strftime('%Y-%m-%d %H:%M:%S'),'sku':tag+'A','seller':tag,'channel_name':'[自动仅退款验收'+key+'] '+TITLES[key]+' '+stamp,'platform_tid':'87'+stamp+str(list(TITLES).index(key)+1).zfill(2),'quantity':qty,'warehouses':[{'code':tag+'V1','quantity':100,'package_count':1}],'received':True,'refunds':{}}
            c=self.data['cases'][key];c['platform_oid']=c['platform_tid']+'1'
            if key=='08':c['warehouses']=[{'code':tag+'V1','quantity':1,'package_count':1},{'code':tag+'V2','quantity':99,'package_count':1}]
            self.save()
        c=self.data['cases'][key];fixture.MANIFEST=OUT/(key+'-fixture.json');dump(fixture.MANIFEST,c)
        if not c.get('prepared'):
            try:fixture.prepare_case(c,self.token,before_conversion=self.callback_setup,order_quantity=c['quantity'])
            finally:self.save()
            c['prepared']=True;self.save()
        helper=ownership.Case.__new__(ownership.Case);helper.c=c;helper.token=self.token;helper.path=OUT/(key+'-fixture.json')
        if not c.get('shipment_ready'):
            o=helper.allocate()
            for entry in o['shipments']:
                if entry['status'] in ['VOIDED','VOIDING','CLOSED']:continue
                s=helper.shipment(entry['id'])
                if s['carrier_match_status']!='MATCHED':
                    result=self.api('/order/orders/shipments/match-carrier',{'items':[{'id':s['id'],'control_version':s['control_version']}]});assert result[0]['success'];s=helper.shipment(s['id'])
                if s['wms_dispatch_state']=='NONE':
                    result=self.api('/order/orders/shipments/dispatch',{'items':[{'id':s['id'],'control_version':s['control_version']}]});assert result[0]['success']
                s=helper.shipment(s['id'])
                if s['wms_dispatch_state']=='PENDING':
                    self.api('/order/orders/shipments/'+str(s['id'])+'/continue-dispatch',{'control_version':s['control_version']})
                until=time.monotonic()+90
                while helper.shipment(s['id'])['wms_dispatch_state']!='ACCEPTED':
                    if time.monotonic()>until:raise RuntimeError('TEST warehouse acceptance not confirmed; refund not injected')
                    time.sleep(1)
                if key!='04':
                    if key=='08':self.partial_ship(c,helper,entry['id'])
                    else:helper.dispatch_ship(entry)
            c['shipment_ready']=True;c['stock_before_refunds']=self.snapshot(c);self.save()
        c['order_url']=BASE+'/oms-order/business?orderId='+str(c['order_id'])
        if not c.get('refund_configured'):self.configure(c)
        self.save();print('PREPARED '+key+' '+c['order_sn'],flush=True)
        return c

    def partial_ship(self,c,helper,ident):
        # The full order remains allocated; only one real source unit is confirmed outbound.
        import xml.etree.ElementTree as ET
        from simulate_qimen_delivery import send
        s=helper.shipment(ident)
        if s['shipped_quantity']>0:return
        until=time.monotonic()+90
        while s['wms_dispatch_state']!='ACCEPTED':
            if time.monotonic()>until:raise RuntimeError('TEST WMS dispatch not ready')
            time.sleep(1);s=helper.shipment(ident)
        target=s['warehouse_target'];assert target['environment']=='TEST'
        connection=next(x for x in self.api('/supplychain/wmsIntegration/connections') if x['id']==target['connection_id']);assert connection['endpoint']=='http://wms-simulator:8080/qimen/gateway'
        credential=next(x for x in json.loads((ROOT/'docker/local/wms-simulator-credentials.json').read_text('utf-8')) if x['app_key']==connection['app_key'] and x['customer_id']==connection['customer_id'])
        line=next((x for x in s['dispatch_lines'] if x['store_code']==c['warehouses'][0]['code']),None)
        if line is None:return  # The second virtual warehouse stays accepted and unshipped.
        root=ET.Element('request');head=ET.SubElement(root,'deliveryOrder')
        for k,v in {'deliveryOrderCode':s['shipment_sn'],'deliveryOrderId':s['wms_external_order'],'warehouseCode':target['external_warehouse'],'ownerCode':target['external_owner'],'status':'PARTDELIVERED','confirmType':'1','outBizCode':'AF_PART_'+s['shipment_sn'],'totalOrderLines':1,'orderConfirmTime':datetime.now().strftime('%Y-%m-%d %H:%M:%S')}.items():ET.SubElement(head,k).text=str(v)
        item=ET.SubElement(ET.SubElement(root,'orderLines'),'orderLine')
        for k,v in {'orderLineNo':line['id'],'itemCode':line['sku_sn'],'ownerCode':target['external_owner'],'batchCode':line['batch_code'],'inventoryType':'ZP','actualQty':1}.items():ET.SubElement(item,k).text=str(v)
        pack=ET.SubElement(ET.SubElement(root,'packages'),'package')
        for k,v in {'packageCode':'AFPK_'+s['shipment_sn'],'expressCode':'AFSF_'+s['shipment_sn'],'logisticsCode':s['warehouse_carrier_code'],'logisticsName':s['carrier_name']}.items():ET.SubElement(pack,k).text=str(v)
        it=ET.SubElement(ET.SubElement(pack,'items'),'item');ET.SubElement(it,'itemCode').text=line['sku_sn'];ET.SubElement(it,'quantity').text='1'
        callback={'callback_body':ET.tostring(root,encoding='unicode')};dump(OUT/'08-partial-outbound.json',callback)
        send(callback,connection,credential)
        until=time.monotonic()+90
        while helper.shipment(ident)['shipped_quantity']!=1:
            if time.monotonic()>until:raise RuntimeError('Partial TEST outbound not posted')
            time.sleep(1)
        c['partial_setup']='两个虚拟仓、同一实体仓的部分出库；已出库1件、仓库待出库2件'

    def configure(self,c):
        channel=c['channel_id'];source=next(x for x in self.api('/order/orders/sources') if x['database_name']==SOURCE and x['enabled']);c['source_key']=source['source_key']
        configs=self.api('/order/refunds/configs')['configs']
        old=next((x for x in configs if x['channel_id']==channel),{})
        self.api('/order/refunds/configs',{'channel_id':channel,'source_key':c['source_key'],'seller_nick':c['seller'],'initial_time':old.get('initial_time',c['created']),'revision':old.get('revision',0),'enabled':1,'auto_convert':1,'interval_seconds':10})
        app=next(x for x in self.api('/channel/platform/apps') if int(x.get('simulation_mode',0))==1)
        self.api(f'/channel/platform/shops/{channel}/binding',{'app_id':app['id'],'expected_shop_id':'800'+str(channel)})
        self.api(f'/channel/platform/shops/{channel}/simulate',{})
        cap=self.api(f'/channel/refundPlatform/{channel}/capability');assert cap['can_query']
        self.api(f'/channel/refundPlatform/{channel}/access',{'revision':cap['revision'],'read_enabled':1,'write_enabled':1,'payment_enabled':1,'ignore_code':1,'ignore_code_confirmed':True})
        # Copy an existing encrypted SIMULATION token only inside this new shop.
        sql(f"UPDATE channel_refund_access x JOIN channel_platform_binding b ON b.company_code=x.company_code AND b.channel_id=x.channel_id JOIN channel_platform_app a ON a.company_code=b.company_code AND a.id=b.app_id SET x.payment_session_cipher=b.token_cipher,x.payment_expires_at=b.token_expires_at,x.payment_app_id=b.app_id,x.payment_seller_id=b.seller_id,x.payment_sub_id='LOCAL_AUTO_ACCEPTANCE' WHERE UPPER(x.company_code)='QM' AND x.channel_id={channel} AND a.simulation_mode=1",CHANNEL)
        assert self.api(f'/channel/refundPlatform/{channel}/capability')['payment_authorized']
        flow=self.api(f'/order/refunds/flow-configs/{channel}')
        self.api(f'/order/refunds/flow-configs/{channel}',{**flow,'enabled':1,'auto_full_cancel':1,'auto_review':1,'auto_pay':1,'auto_cancel_feedback':1,'auto_intercept':1,'funds_owner':'OMS','payment_limit':'500.00'})
        p=self.api(f'/order/refunds/parcel-configs/{channel}')['policy']
        self.api(f'/order/refunds/parcel-configs/{channel}',{**p,'merge_mode':'AUTO','auto_create':1,'auto_dispatch':1,'auto_allocate':1,'allocation_mode':'RULE','allow_partial_allocation':1,'create_store_code':c['warehouses'][0]['code'],'create_return_address':'[自动仅退款验收] 测试退回地址','create_return_contact':'TEST-AUTO-REFUND','store_codes':[w['code'] for w in c['warehouses']],'logistics_sources':['PLATFORM'],'collect_seconds':120})
        c['refund_configured']=True;c['small_rule_available']=False;self.save()

    def inject(self,c,key,fee,status='WAIT_SELLER_AGREE',prior_setup=False):
        if key not in c['refunds']:
            rid='88'+self.data['run_id']+c.get('slot',str(list(TITLES).index(c['key'])+1).zfill(2))+str(len(c['refunds'])+1)
            now=datetime.now().strftime('%Y-%m-%d %H:%M:%S')
            p={'refund_id':rid,'tid':c['platform_tid'],'oid':c['platform_oid'],'seller_nick':c['seller'],'status':status,'refund_fee':str(fee),'num':c['quantity'],'has_good_return':False,'dispute_type':'REFUND','refund_phase':'aftersale','refund_version':'1','reason':'[自动仅退款验收'+c['key']+'] '+c['title'],'modified':now,'created':now,'company_name':'SF','sid':'AFRET_'+c['case_id']}
            raw=json.dumps({'refund_get_response':{'refund':p}},ensure_ascii=False)
            sql('INSERT IGNORE INTO jdp_tb_refund(refund_id,tid,oid,status,seller_nick,created,modified,jdp_modified,jdp_response) VALUES ('+','.join(quote(v) for v in [rid,c['platform_tid'],c['platform_oid'],status,c['seller'],now,now,now,raw])+')',SOURCE)
            sql("INSERT IGNORE INTO channel_refund_sim(company_code,channel_id,refund_id,payload,modified) VALUES ('qm',"+str(c['channel_id'])+','+','.join(quote(v) for v in [rid,raw,now])+')',CHANNEL)
            c['refunds'][key]={'refund_id':rid,'amount':str(fee),'setup_only':prior_setup,'injected_at':now};self.save()
        ref=c['refunds'][key]
        # The shared Quartz job is paused. Target only this owned shop/refund;
        # never run or enable the company-wide/global scan endpoint.
        if not ref.get('intake_task_id'):
            task=self.api('/order/refunds/sync',{'channel_id':c['channel_id'],'mode':'IDS','refund_ids':[ref['refund_id']],'request_key':'AF_IDS_'+ref['refund_id']})
            ref['intake_task_id']=task['id'];self.save()
        until=time.monotonic()+160
        while True:
            rows=self.api('/order/refunds/platform?number='+ref['refund_id'])['rows']
            row=next((r for r in rows if r['refund_id']==ref['refund_id']),None)
            if row and row.get('business_refund_id'):
                ref['id']=row['business_refund_id'];ref['url']=BASE+'/oms-order/businessRefunds?id='+str(ref['id']);b=self.api('/order/refunds/business/'+str(ref['id']));ref['refund_sn']=b['refund_sn'];self.save();return ref
            if time.monotonic()>until:raise RuntimeError('Automatic source intake/conversion not completed for '+c['key'])
            time.sleep(2)

    def prior(self,c,amount,purpose):
        ref=self.inject(c,'prior',amount,'SUCCESS',True)
        b=self.api('/order/refunds/business/'+str(ref['id']))
        if b['review_state']!='APPROVED':
            self.api('/order/refunds/business/'+str(b['id'])+'/review',{'control_version':b['control_version'],'platform_version':b['platform']['version'],'purpose':purpose,'goods_action':'KEEP','reason':'[仅前置数据] 已完成的纯资金退款，非本轮自动审批通过','evidence':'LOCAL-SIMULATION-PRIOR-'+c['case_id']})
        ref['setup_description']='平台成功退款前置；通过业务API确认货物保留，不计为自动审批通过';self.save()
        return ref

    def observe(self,c,ref,seconds=100):
        assert ref['id'] in [r.get('id') for r in c['refunds'].values()]
        self.api('/order/refunds/business/'+str(ref['id'])+'/flow/retry',{})
        ref['test_driver']='定向补拉该测试退款，定向唤醒该退款自动流程；全局Quartz保持暂停，未验定时接入'
        self.save();last_drive=time.monotonic()
        until=time.monotonic()+seconds
        while True:
            raw=sql(f'SELECT state,last_message FROM oms_refund_flow_task WHERE company_code=\'QM\' AND refund_id={ref["id"]}',ORDER)
            b=self.api('/order/refunds/business/'+str(ref['id']))
            if raw.startswith('MANUAL\t') or b['state']=='COMPLETED' or time.monotonic()>until:
                flow=raw.split('\t',1);result={'business_state':b['state'],'review_state':b['review_state'],'goods_action':b['goods_action'],'goods_state':b['goods_state'],'funds_state':b['funds_state'],'fulfillment_stage':b.get('initial_fulfillment_stage'),'flow_state':flow[0] if raw else 'NOT_CREATED','flow_message':flow[1] if len(flow)>1 else '', 'plans':self.api('/order/refunds/business/'+str(ref['id'])+'/goods')['plans'], 'platform_operations':sql(f'SELECT action,state,result_message FROM oms_refund_operation WHERE company_code=\'QM\' AND refund_id={ref["id"]} ORDER BY id',ORDER)}
                dump(OUT/(c['key']+'-'+str(ref['id'])+'-business.json'),b)
                ref['observation']=result;c['stock_after_refunds']=self.snapshot(c);self.save();return result
            if time.monotonic()-last_drive>=25:
                sequence=int(ref.get('driver_sequence',0))+1;ref['driver_sequence']=sequence;self.save()
                self.api('/order/refunds/sync',{'channel_id':c['channel_id'],'mode':'IDS','refund_ids':[ref['refund_id']],'request_key':'AF_TICK_'+ref['refund_id']+'_'+str(sequence)})
                last_drive=time.monotonic()
            time.sleep(2)

    def execute(self,key):
        c=self.prepare(key)
        if c.get('finished'):return
        if key in ['03','09','10']:self.prior(c,10 if key=='03' else 20,'COMPENSATION' if key=='03' else 'DIFFERENCE')
        fees={'01':'10.00','02A':'20.00','02B':'20.01','03':'15.00','04':'100.00','05':'100.00','06':'100.00','07':'200.00','08':'300.00','09':'80.00','10':'100.00'}
        ref=self.inject(c,'target',fees[key]);observed=self.observe(c,ref,180 if key=='04' else 100)
        unchanged=c['stock_before_refunds']==c['stock_after_refunds']
        c['inventory_unchanged']=unchanged
        if key=='04':
            c['status']='PASS' if observed['business_state']=='COMPLETED' and observed['funds_state']=='SUCCESS' and observed['goods_state']=='DONE' else 'FAIL'
            c['finding']='已自动截单并退款完成' if c['status']=='PASS' else observed['flow_message'] or '截单/平台自动流程未完成'
        elif key=='02B':
            c['status']='PARTIAL';c['finding']='20.01元未自动付款，库存未变化；当前没有20元小额规则，无法证明是命中阈值保护'
        elif key=='10':
            c['status']='PARTIAL';c['finding']='超额申请未自动付款；停止原因是用途/货物方案待审核，未证明按剩余80元识别累计超额'
        else:
            c['status']='FAIL';c['finding']=observed['flow_message'] or '目标自动流程未完成'
        c['downstream_not_reached']=[] if c['status']=='PASS' else ['本地自动审批后的平台审核/付款','货物方案自动创建及后续仓库流程']
        c['finished']=True;self.save();print(c['status']+' '+key+' '+c['finding'],flush=True)

    def report(self):
        from verify_merged_composite_gift_refunds import Acceptance
        a=Acceptance();p=self.data['protected']
        verification={'protected_merged_stock_unchanged':a.snapshot()==p['merged_stock'],'protected_preceding_stock_unchanged':a.protected_order()==p['preceding_stock'],'existing_settings_unchanged':self.settings_hash()==p['old_settings_hash'],'queue_controls_unchanged':sql('SELECT business_type,desired_mode,concurrency FROM sys_mq_queue_policy ORDER BY business_type','ry-cloud')==p['queues'],'job_controls_unchanged':sql('SELECT job_id,status,cron_expression FROM sys_job ORDER BY job_id','ry-cloud')==p['jobs']}
        assert all(verification.values()),verification
        rows=[]
        for key,c in self.data['cases'].items():
            rows.append({'case':key,'scenario':c['title'],'status':c.get('status','INCOMPLETE'),'finding':c.get('finding',''),'channel_id':c.get('channel_id'),'channel_name':c['channel_name'],'order_sn':c.get('order_sn'),'order_url':c.get('order_url'),'refunds':c['refunds'],'stock_before_refunds':c.get('stock_before_refunds'),'stock_after_refunds':c.get('stock_after_refunds'),'partial_setup':c.get('partial_setup')})
        result={'run_id':self.data['run_id'],'results':rows,'verification':verification,'retained':True,'production_code_modified':False,'target_refunds_reviewed_manually':False,'target_refunds_paid_manually':False,'physical_stock_changed_by_sql':False,'platform_mode':'SIMULATION','warehouse_environment':'TEST','driver':'Scoped test-shop refund-ID intake and scoped per-refund automatic-flow wakeup; original global Quartz job remains paused. Scheduled intake not verified.'}
        dump(OUT/'results.json',result)
        lines=['# 发货后仅退款自动流程验收','', '批次：'+self.data['run_id'],'','测试数据永久保留在本地环境。平台为模拟授权，仓库为TEST接口。目标退款未人工审核、未人工付款、未人工补建退回安排。小额免退货20元为目标规则，当前系统没有该配置。','', '| 用例 | 场景 | 结果 | 业务订单 | 退款入口 | 实际停止原因 |','|---|---|---|---|---|---|']
        for r in rows:
            links='；'.join('['+x.get('refund_sn',x['refund_id'])+']('+x['url']+')'+('（前置）' if x['setup_only'] else '') for x in r['refunds'].values() if x.get('url'))
            lines.append('|'+r['case']+'|'+r['scenario']+'|'+r['status']+'|['+str(r['order_sn'])+']('+str(r['order_url'])+')|'+links+'|'+r['finding'].replace('|','/')+'|')
        lines += ['', 'PASS表示指定输入触发后的自动处理链路完成；PARTIAL表示观察到停止/保护，但没有验证目标规则；FAIL表示目标自动流程未完成。原全局Quartz任务暂停，未运行全局扫描；本次仅按测试店铺/退款ID补拉及唤醒自动流程，定时接入不计通过。各商品初始库存100，全部库存阶段快照及原始业务详情见 manifest.json / results.json。', '', '用例08目前采用两个虚拟仓、同一实体仓的真实部分出库。跨实体仓分支未覆盖，应另行补验。','', '已有订单库存、旧店铺配置、队列开关与调度配置核对：'+json.dumps(verification,ensure_ascii=False)]
        (OUT/'验收结果.md').write_text('\n'.join(lines)+'\n','utf-8')
        print('Retained report: '+str(OUT/'验收结果.md'),flush=True)


def main():
    run=Run();run.ready();run.protect()
    selected=sys.argv[1:] or list(TITLES)
    for key in selected:
        try:run.execute(key)
        except Exception as e:
            run.save();print('STOP '+key+' '+type(e).__name__+' '+str(e)[:300],flush=True);raise
    run.report()


if __name__=='__main__':main()
