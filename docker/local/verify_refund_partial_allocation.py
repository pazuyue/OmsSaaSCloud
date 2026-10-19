"""Retained TEST case: enable the shop rule, allocate an existing 3/4 shared THK receipt, inspect UI and deduplicate."""
import json
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE
from test_multi_virtual_shipping import wait_for
from verify_refund_shared_thk import stock_quantity
from wms_qimen_simulator import sign
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'outputs/refund-partial-allocation'
def main():
    OUT.mkdir(parents=True,exist_ok=True)
    c=json.loads((ROOT/'outputs/exchange-ownership/refundsharedthk-case.json').read_text('utf-8'));token=login()
    def api(path,body=None):return request(path,token=token,method='POST' if body is not None else 'GET',payload=body).get('data')
    base='/order/refunds/business/'+str(c['refunds'][2]['id'])
    def parcel():return next(p for p in api(base+'/parcels') if p['id']==c['parcel_id'])
    try:
        def ready():
            try:return parcel()
            except (AssertionError,OSError):return None
        wait_for(ready,lambda r:r is not None,'Refund parcel services not ready',150)
        p=parcel();doc=p['warehouse_document'];assert doc['return_sn']==c['return_sn'] and doc['cancel_state']=='NONE'
        connection=next(x for x in api('/supplychain/wmsIntegration/connections') if x['id']==c['parcel_connection'])
        assert connection['environment']=='TEST' and connection['endpoint']=='http://wms-simulator:8080/qimen/gateway'
        policy=api('/order/refunds/parcel-configs/'+str(c['channel_id']))['policy']
        if policy.get('allocation_mode')!='RULE' or not policy.get('allow_partial_allocation'):
            policy.update(allocation_mode='RULE',allow_partial_allocation=1,allocation_order='REFUND_CREATED')
            api('/order/refunds/parcel-configs/'+str(c['channel_id']),policy)
        policy=api('/order/refunds/parcel-configs/'+str(c['channel_id']))['policy'];assert policy['allocation_mode']=='RULE' and policy['allow_partial_allocation']==1
        p=parcel();event=next(e for e in p['receipts'] if e['message_id']=='SHARED_THK_PARTIAL_3');preview=None
        if event['state']=='WAIT_ALLOCATION':
            stock=stock_quantity(c)
            preview=api(f"{base}/parcels/{p['id']}/receipts/{event['id']}/automatic",{'execute':False})
            assert preview['status']=='READY',preview
            assert [r['assigned'] for r in preview['rows']]==[2,1,0],preview
            assert stock_quantity(c)==stock,'Preview changed stock'
            assert parcel()['receipts'][0]['state']=='WAIT_ALLOCATION'
        with sync_playwright() as pw:
            browser=pw.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1800,'height':1160});context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=context.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
            page.goto(BASE+'/oms-order/afterSaleRules/returns?channelId='+str(c['channel_id']),wait_until='networkidle')
            expect(page.locator('.return-rules')).to_contain_text('部分实收 · 申请时间优先')
            editor=page.locator('.return-rules-drawer')
            if not editor.is_visible():page.get_by_role('button',name='编辑',exact=True).click()
            expect(editor).to_contain_text('3. 实收分配');expect(editor.locator('.el-radio').filter(has_text='按规则自动分配')).to_have_class(__import__('re').compile('is-checked'))
            page.screenshot(path=str(OUT/'shop-allocation-rule.png'),animations='disabled')
            page.goto(BASE+'/oms-order/businessRefunds?id='+str(c['refunds'][2]['id']),wait_until='networkidle')
            dialog=page.get_by_role('dialog',name='业务退款详情 · 退货退款',exact=True);expect(dialog).to_be_visible();dialog.get_by_role('button',name='查看本退款货物进度',exact=True).click()
            dialog.locator('.plan').get_by_role('button',name='查看包裹及处理实收',exact=True).first.click();panel=page.locator('.refund-parcel-drawer');expect(panel).to_be_visible()
            if preview:
                panel.get_by_role('button',name='按当前规则预览分配',exact=True).click();review=page.get_by_role('dialog',name='按当前规则预览实收分配',exact=True);expect(review).to_be_visible();expect(review).to_contain_text('OMS创建时间（平台申请时间缺失）')
                expect(review.get_by_role('button',name='确认按规则分配',exact=True)).to_be_disabled();page.screenshot(path=str(OUT/'two-one-zero-preview.png'),animations='disabled')
                review.get_by_text('已核对分配结果，确认按当前规则分配实收并入账',exact=True).click();review.get_by_role('button',name='确认按规则分配',exact=True).click();expect(review).not_to_be_visible(timeout=30000)
            expect(panel).to_contain_text('已按规则自动分配',timeout=30000);expect(panel).to_contain_text('本次未分配到实收');expect(panel).to_contain_text('分配后待退回 1 件')
            page.screenshot(path=str(OUT/'shared-parcel-allocation-result.png'),animations='disabled');panel.locator('.el-table__expand-icon').first.click();expect(panel).to_contain_text('OMS创建时间（平台申请时间缺失）');panel.locator('.el-table__expanded-cell').first.screenshot(path=str(OUT/'allocation-priority-audit.png'),animations='disabled');panel.get_by_role('button',name='返回退款单',exact=True).click();expect(dialog).to_contain_text('本次未分配到实收');page.screenshot(path=str(OUT/'third-tk-still-awaits-one.png'),animations='disabled');assert not errors,errors;browser.close()
        for b in c['refunds']:api('/order/refunds/business/'+str(b['id'])+'/goods/refresh',{})
        p=parcel();event=next(e for e in p['receipts'] if e['id']==event['id']);assert event['state']=='ALLOCATED'
        audit=event['allocation_metadata'];audit=json.loads(audit) if isinstance(audit,str) else audit
        assert audit['method']=='RULE_PARTIAL' and audit['status']=='APPLIED' and [r['assigned'] for r in audit['rows']]==[2,1,0]
        plans=[api('/order/refunds/business/'+str(b['id'])+'/goods')['plans'][0] for b in c['refunds']]
        assert [p['accepted_quantity'] for p in plans]==[2,1,0] and [p['posted_quantity'] for p in plans]==[2,1,0],plans
        assert stock_quantity(c)==c['stock_before']+3
        assert all(api('/order/refunds/business/'+str(b['id']))['goods_state']!='DONE' for b in c['refunds'])
        # Replay the original signed callback; one message stays one receipt and stock increases only once.
        logs=api('/supplychain/wmsIntegration/log-center?'+urllib.parse.urlencode({'numberType':'DOCUMENT','number':c['return_sn'],'allTime':'true','action':'REFUND_RETURN_RECEIPT'}))['rows']
        wire=next(api('/supplychain/wmsIntegration/logs/'+str(log['id']))['request_body'] for log in logs if 'SHARED_THK_PARTIAL_3' in api('/supplychain/wmsIntegration/logs/'+str(log['id']))['request_body'])
        credential=next(x for x in json.loads((ROOT/'docker/local/wms-simulator-credentials.json').read_text('utf-8')) if x['app_key']==connection['app_key'] and x['customer_id']==connection['customer_id'])
        params={'app_key':connection['app_key'],'customerId':connection['customer_id'],'method':'taobao.qimen.returnorder.confirm','v':'2.0','format':'xml','sign_method':'md5','timestamp':datetime.now().strftime('%Y-%m-%d %H:%M:%S')};params['sign']=sign(params,wire,credential['secret'])
        req=urllib.request.Request(BASE+'/prod-api/supplychain/wmsCallback/qm/'+connection['callback_key']+'?'+urllib.parse.urlencode(params),data=wire.encode(),headers={'Content-Type':'application/xml;charset=UTF-8'},method='POST')
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req,timeout=50) as response:reply=ET.fromstring(response.read())
        assert reply.findtext('flag')=='success' and stock_quantity(c)==c['stock_before']+3
        assert len([e for e in parcel()['receipts'] if e['message_id']=='SHARED_THK_PARTIAL_3'])==1
        result={'parcel_sn':c['parcel_sn'],'thk':c['return_sn'],'rule_revision':audit['policy_revision'],'refunds':[{**b,'allocated':p['accepted_quantity'],'posted':p['posted_quantity'],'remaining':p['quantity']-p['accepted_quantity']} for b,p in zip(c['refunds'],plans)],'stock_added':3,'duplicate_callback_no_extra_stock':True,'url':BASE+'/oms-order/businessRefunds?id='+str(c['refunds'][2]['id'])}
        (OUT/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8');print('PASS retained case 2/1/0, preview read-only, UI explicit zero, stock +3 once, remaining1 not closed')
    finally:request('/auth/logout',token=token,method='DELETE')
if __name__=='__main__':main()
