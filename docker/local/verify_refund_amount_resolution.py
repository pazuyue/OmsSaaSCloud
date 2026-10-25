"""Retained TEST-source correction: difference20, over-limit100, buyer correction80, automatic return/payment.

No local target review, flow retry, platform consent/payment or direct business/stock edits.
The original over-limit acceptance refund122 is read-only and remains pending.
"""
import json
import time
from datetime import datetime,timedelta
from decimal import Decimal
from pathlib import Path
import verify_return_refund_auto as suite
from test_multi_virtual_shipping import sql,quote

OUT=suite.ROOT/'outputs/return-refund-amount-resolution'
suite.OUT=OUT
suite.TITLES['08']='超额转人工后协商修改平台申请为80元'

def main():
    run=suite.Acceptance()
    old=run.api('/order/refunds/business/122')
    c=run.prepare('08');prior=run.authorize_difference(c)
    until=time.monotonic()+540
    while time.monotonic()<until:
        first=run.observe(c,prior)
        if first and first['business']['state']=='COMPLETED':break
        time.sleep(3)
    else:raise RuntimeError('Prior authorized difference did not complete automatically')
    assert first['business']['review_by']=='自动售后规则'
    c.setdefault('stock_after_difference',run.snapshot(c));target=run.inject(c,'target',100)
    if not c.get('corrected'):
        while time.monotonic()<until:
            blocked=run.observe(c,target)
            if blocked and blocked['flow'][0]=='MANUAL' and '剩余可退 80.00' in blocked['flow'][-1]:break
            time.sleep(3)
        else:raise RuntimeError('Over-limit request did not reach the expected manual protection')
    if not c.get('corrected'):
        assert blocked['business']['review_state']=='PENDING' and blocked['business']['funds_state']=='NONE'
        assert not blocked['goods']['plans'] and not blocked['parcels']
        suite.dump(OUT/'before-buyer-correction.json',blocked)
        # Only the dedicated simulated vendor source changes. OMS adopts its new source event.
        query="SELECT payload FROM channel_refund_sim WHERE company_code='qm' AND channel_id="+str(c['channel_id'])+' AND refund_id='+quote(target['refund_id'])
        p=json.loads(sql(query,suite.CHANNEL))['refund_get_response']['refund']
        assert p['status']=='WAIT_SELLER_AGREE' and Decimal(str(p['refund_fee']))==100
        p.update(refund_fee='80.00',refund_version=str(int(p['refund_version'])+1),modified=max(datetime.now(),datetime.strptime(p['modified'],'%Y-%m-%d %H:%M:%S')+timedelta(seconds=1)).strftime('%Y-%m-%d %H:%M:%S'),reason='[TEST协商结果] 扣除已退差价20元，本次申请改为80元，原商品与赠品一起退回')
        sql("UPDATE channel_refund_sim SET payload="+quote(json.dumps({'refund_get_response':{'refund':p}},ensure_ascii=False))+',modified='+quote(p['modified'])+" WHERE company_code='qm' AND channel_id="+str(c['channel_id'])+' AND refund_id='+quote(target['refund_id']),suite.CHANNEL)
        run.publish(c,target);c['corrected']=True;target['amount']='80.00';run.save();print('SOURCE CORRECTED 100 -> 80',target['refund_sn'],flush=True)
    while time.monotonic()<until:
        final=run.observe(c,target);b=final['business']
        if b['state']=='COMPLETED':break
        if b['platform']['platform_status']=='WAIT_BUYER_RETURN_GOODS':run.buyer(c,target)
        for parcel in final['parcels']:run.receive(c,[final],parcel)
        time.sleep(3)
    else:raise RuntimeError('Corrected request did not resume; see retained observations')
    final=run.observe(c,target);first=run.observe(c,prior);stock=run.snapshot(c)
    assert final['business']['review_by']=='自动售后规则' and final['business']['funds_state']=='SUCCESS'
    assert str(final['business']['refund_fee']) in ['80.0','80.00','80']
    assert all(r[1:]==['100','100','0','0'] for r in stock['company']),stock
    ids=','.join(str(r['id']) for r in [prior,target])
    manual=int(sql("SELECT COUNT(*) FROM oms_refund_event WHERE company_code='QM' AND refund_id IN ("+ids+") AND action='REVIEW' AND actor NOT IN ('自动售后规则','自动售后流程')",suite.ORDER));assert manual==0
    pays=sql("SELECT refund_id,COUNT(*) FROM oms_refund_operation WHERE company_code='QM' AND refund_id IN ("+ids+") AND action='PAY' GROUP BY refund_id ORDER BY refund_id",suite.ORDER);assert len(pays.splitlines())==2 and all(s.endswith('\t1') for s in pays.splitlines())
    assert all(r[2:]==['100','100','0'] for r in stock['warehouse'])
    assert c['stock_after_difference']['channel']==stock['channel']
    for ref in [prior,target]:
        assert int(sql("SELECT COUNT(*) FROM channel_refund_command WHERE company_code='qm' AND channel_id="+str(c['channel_id'])+' AND refund_id='+quote(ref['refund_id'])+" AND method='taobao.rp.refunds.agree'",suite.CHANNEL))==1
    after=run.api('/order/refunds/business/122')
    for field in ['state','review_state','review_by','refund_fee','funds_state','control_version']:assert old[field]==after[field],field
    assert after['refund_sn']=='TK7060be00492d406a91a6941da82e74b4' and after['review_state']=='PENDING'
    result={'status':'PASS','run_id':run.data['run_id'],'order_sn':c['order_sn'],'order_url':c['order_url'],'refunds':c['refunds'],'scenario':'先自动退差价20，申请退货退款100被拦住；模拟买家协商将平台申请修改为80，来源同步后自动审核、合包退货入库、退款80，累计100','manual_review_events':manual,'manual_refund_actions':False,'flow_retry_called':False,'before':{'requested':100,'other_confirmed':20,'limit':80},'after':{'target_paid':80,'cumulative_paid':100},'stock_before':c['stock_after_difference'],'stock_after':stock,'pay_requests':pays,'original_122_unchanged':True,'platform_mode':'SIMULATION','warehouse_environment':'TEST'}
    suite.dump(OUT/'results.json',result);c['done']=True;run.save();print('PASS buyer-corrected source resumes automatically; original protected refund122 unchanged',flush=True)

if __name__=='__main__':main()
