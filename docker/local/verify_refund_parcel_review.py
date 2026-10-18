"""Keep TK9 blocked for user acceptance; prove the full reviewed supplement on a separate TEST fixture."""
import json
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE
from test_multi_virtual_shipping import wait_for
from wms_qimen_simulator import sign
import verify_refund_parcel_append as fixture

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'outputs/refund-parcel-review'

def main():
    OUT.mkdir(parents=True,exist_ok=True)
    fixture.CASE_KIND='refundreview';fixture.OUT=OUT/'fixture';case_path=ROOT/'outputs/exchange-ownership/refundreview-case.json';existing=json.loads(case_path.read_text('utf-8')) if case_path.exists() else {}
    if not existing.get('review_started'):fixture.main()
    c=json.loads(case_path.read_text('utf-8'));c['review_started']=True;case_path.write_text(json.dumps(c,ensure_ascii=False,indent=2),encoding='utf-8');token=login()
    def api(path,body=None):
        r=request(path,token=token,payload=body,method='POST' if body is not None else 'GET');return r.get('data',r)
    def plan(refund):return api(f"/order/refunds/business/{refund}/goods")['plans'][0]
    def blocked(path,body):
        try:api(path,body)
        except AssertionError:return
        raise AssertionError('Expected business block: '+path)
    try:
        # Refresh the exact user's acceptance document through its normal processing path.
        api('/order/refunds/business/9/parcels/auto-run',{})
        original=api('/order/refunds/business/9');issue=original['parcel_issues'][0]
        assert issue['code']=='APPEND_AFTER_RECEIPT' and issue['title']=='合包追加待核对' and not issue['has_document']
        assert issue['target_parcel_sn']=='RPK44b2934f122e4cf19df31e4d563142b8'
        p=plan(9);blocked(f"/order/refunds/business/9/goods/plans/{p['id']}/documents/0/dispatch",{'control_version':original['control_version'],'plan_version':p['control_version']})
        assert plan(9)['actual'] is None
        with sync_playwright() as pw:
            browser=pw.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1600,'height':1100});context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=context.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
            page.goto(BASE+'/oms-order/businessRefunds?id=9',wait_until='networkidle');detail=page.get_by_role('dialog',name='业务退款详情 · 退货退款',exact=True);card=detail.locator('.parcel-issue-task');expect(card.get_by_text('合包追加待核对',exact=True)).to_be_visible();expect(card.get_by_text('目标包裹已有实收回传',exact=False)).to_be_visible();page.screenshot(path=str(OUT/'TK9-detail-issue.png'),animations='disabled')
            card.get_by_role('button',name='核对合包归属',exact=True).click();review=page.get_by_role('dialog',name='核对合包归属',exact=True);expect(review.get_by_text('尚未关联目标包裹，未生成本退款THK',exact=True)).to_be_visible();review.locator('label.el-radio').filter(has_text='确实在原包裹中').click();expect(review.get_by_text('仓库尚未确认支持收货后补单，暂不能补充关联',exact=True)).to_be_visible();expect(review.get_by_role('button',name='确认补充关联',exact=True)).to_be_disabled();page.screenshot(path=str(OUT/'TK9-guided-review.png'),animations='disabled');review.get_by_role('button',name='返回',exact=True).click()
            detail.get_by_role('button',name='处理货物与退回单',exact=True).click();expect(detail.get_by_role('button',name='下发退回单至仓库',exact=True)).to_be_disabled();detail.get_by_role('button',name='关闭',exact=True).click()
            page.goto(BASE+'/oms-order/businessRefunds?'+urllib.parse.urlencode({'number':original['refund_sn']}),wait_until='networkidle');expect(page.get_by_role('button',name='处理合包问题',exact=True)).to_be_visible();expect(page.get_by_text('合包追加待核对',exact=True).first).to_be_visible();page.screenshot(path=str(OUT/'TK9-list-issue.png'),animations='disabled')
            # Independent case: existing receipt cannot be bypassed before late capability is explicitly enabled.
            b=c['refunds'][3];rid=b['id'];api(f'/order/refunds/business/{rid}/parcels/auto-run',{});current=api(f'/order/refunds/business/{rid}');p=plan(rid)
            connection=next(x for x in api('/supplychain/wmsIntegration/connections') if x['id']==c['parcel_connection']);assert connection['environment']=='TEST' and connection['endpoint']=='http://wms-simulator:8080/qimen/gateway'
            if not p['actual'] and p['parcel_state']=='NEED_CHECK':
                ctx=api(f"/order/refunds/business/{rid}/parcels/plans/{p['id']}/review")
                if not int(connection['shared_return_late_append']):blocked(f"/order/refunds/business/{rid}/parcels/plans/{p['id']}/review",{'decision':'SAME_PARCEL','confirmed':True,'buyer_verified':True,'warehouse_verified':True,'reason':'[验收] 仓库实物核实','evidence':'TEST-LATE-APPEND','control_version':ctx['control_version'],'plan_version':ctx['plan']['version'],'parcel_version':ctx['target']['control_version']})
                connection['shared_return_late_append']=1;api('/supplychain/wmsIntegration/connections',connection)
                page.goto(BASE+f'/oms-order/businessRefunds?id={rid}',wait_until='networkidle');detail=page.get_by_role('dialog',name='业务退款详情 · 退货退款',exact=True);detail.locator('.parcel-issue-task').get_by_role('button',name='核对合包归属',exact=True).click();review=page.get_by_role('dialog',name='核对合包归属',exact=True);review.locator('label.el-radio').filter(has_text='确实在原包裹中').click();review.locator('label.el-checkbox').filter(has_text='已核实本退款商品确实装在').click();review.locator('label.el-checkbox').filter(has_text='已取得仓库确认').click();review.locator('.el-form-item').filter(has_text='核对依据').locator('textarea').fill('[验收] 仓库核实本退款1件确在原包裹，允许收货后补单');review.locator('.el-form-item').filter(has_text='仓库／买家凭证').locator('textarea').fill('TEST-WAREHOUSE-SUPPLEMENT-PROOF');review.locator('label.el-checkbox').filter(has_text='已核实本退款实物、数量').click();review.get_by_role('button',name='确认补充关联',exact=True).click();expect(review).not_to_be_visible();expect(detail.get_by_text('补充关联完成，待下发THK',exact=True)).to_be_visible();assert plan(rid)['actual'] is None and fixture.stock_quantity(c)==0;api(f'/order/refunds/business/{rid}/parcels/auto-run',{});assert plan(rid)['actual'] is None;page.screenshot(path=str(OUT/'reviewed-await-manual-dispatch.png'),animations='disabled')
                detail.get_by_role('button',name='去下发补充THK',exact=True).click();detail.get_by_role('button',name='下发本退款补充退回单',exact=True).click();wait_for(lambda:plan(rid),lambda p:p['actual'] and p['actual']['dispatch_state']=='ACCEPTED','Supplement THK was not accepted',90)
            else:
                page.goto(BASE+f'/oms-order/businessRefunds?id={rid}',wait_until='networkidle');detail=page.get_by_role('dialog',name='业务退款详情 · 退货退款',exact=True);expect(detail).to_be_visible();detail.get_by_role('button',name='处理货物与退回单',exact=True).click()
            if not plan(rid)['actual']:
                page.goto(BASE+f'/oms-order/businessRefunds?id={rid}',wait_until='networkidle');detail=page.get_by_role('dialog',name='业务退款详情 · 退货退款',exact=True);detail.get_by_role('button',name='去下发补充THK',exact=True).click();detail.get_by_role('button',name='下发本退款补充退回单',exact=True).click();wait_for(lambda:plan(rid),lambda p:p['actual'] and p['actual']['dispatch_state']=='ACCEPTED','Supplement THK not ready',90)
            p=plan(rid);parcel=api(f'/order/refunds/business/{rid}/parcels')[0];assert len(parcel['members'])==4
            pending=next((e for e in parcel['receipts'] if e['state']=='WAIT_ALLOCATION'),None)
            if pending:
                assert fixture.stock_quantity(c)==0
                detail.get_by_role('button',name='处理货物与退回单',exact=True).click();panel=detail.locator('.refund-parcels');panel.get_by_role('button',name='核对商品归属',exact=True).click();allocation=page.get_by_role('dialog',name='核对合包实收归属',exact=True);allocation.locator('label.el-radio').filter(has_text='确认各商品归属').click();allocation.locator('.allocation .el-select').click();page.locator('.el-select-dropdown:visible .el-select-dropdown__item').filter(has_text=p['actual']['return_sn']).click();allocation.locator('.allocation .el-input-number').nth(0).locator('input').fill('1');allocation.locator('.el-form-item').filter(has_text='处理依据').locator('textarea').fill('[验收] 原回传1件经仓库核实归补充退款');allocation.locator('.el-form-item').filter(has_text='核对凭证').locator('textarea').fill('TEST-ORIGINAL-RECEIPT-OWNERSHIP');allocation.locator('label.el-checkbox').filter(has_text='已核实实际包裹').click();allocation.get_by_role('button',name='确认提交',exact=True).click();expect(allocation).not_to_be_visible()
            assert fixture.stock_quantity(c)==1;assert plan(rid)['actual']['posted_quantity']==1
            parcel=api(f'/order/refunds/business/{rid}/parcels')[0];event=next(e for e in parcel['receipts'] if e['message_id']=='APPEND_FREEZE');assert event['state']=='ALLOCATED'
            credential=next(x for x in json.loads((ROOT/'docker/local/wms-simulator-credentials.json').read_text('utf-8')) if x['app_key']==connection['app_key'] and x['customer_id']==connection['customer_id']);logs=api('/supplychain/wmsIntegration/log-center?'+urllib.parse.urlencode({'numberType':'DOCUMENT','number':c['parcel_sn'],'allTime':'true','action':'REFUND_PARCEL_RECEIPT'}))['rows'];wire=api('/supplychain/wmsIntegration/logs/'+str(logs[-1]['id']))['request_body'];params={'app_key':connection['app_key'],'customerId':connection['customer_id'],'method':'taobao.qimen.returnorder.confirm','v':'2.0','format':'xml','sign_method':'md5','timestamp':datetime.now().strftime('%Y-%m-%d %H:%M:%S')};params['sign']=sign(params,wire,credential['secret']);req=urllib.request.Request(BASE+'/prod-api/supplychain/wmsCallback/qm/'+connection['callback_key']+'?'+urllib.parse.urlencode(params),data=wire.encode(),headers={'Content-Type':'application/xml;charset=UTF-8'},method='POST')
            with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req,timeout=50) as response:reply=ET.fromstring(response.read())
            assert reply.findtext('flag')=='success';assert fixture.stock_quantity(c)==1
            review_logs=api('/supplychain/wmsIntegration/log-center?'+urllib.parse.urlencode({'numberType':'REFUND','number':b['refund_sn'],'allTime':'true','action':'REFUND_PARCEL_LINK_REVIEW'}))['rows'];assert review_logs
            assert all(plan(old['id'])['actual']['accepted_quantity']==0 for old in c['refunds'][:3])
            detail.get_by_role('button',name='刷新详情',exact=True).click();detail.get_by_role('button',name='处理货物与退回单',exact=True).click();detail.locator('.refund-parcels').scroll_into_view_if_needed();page.screenshot(path=str(OUT/'supplement-received-once.png'),animations='disabled');assert not errors,errors;browser.close()
        # The user's document is still pending, with no new THK and no inventory effects.
        assert api('/order/refunds/business/9')['parcel_issues'][0]['code']=='APPEND_AFTER_RECEIPT' and plan(9)['actual'] is None
        evidence={'user_refund':'TK811a9ed2dd6a492e80665e628464aff4','user_refund_unchanged':True,'user_url':BASE+'/oms-order/businessRefunds?id=9','reviewed_refund':b['refund_sn'],'reviewed_url':BASE+f'/oms-order/businessRefunds?id={rid}','reviewed_parcel':c['parcel_sn'],'manual_dispatch_required':True,'stock_added_once':1,'original_thks_received':0,'real_platform_writes':False};(OUT/'case.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf-8');print('PASS visible issue + guided choices; manual supplement -> new THK -> explicit original receipt allocation; stock +1 once; user TK9 remains pending')
    finally:request('/auth/logout',token=token,method='DELETE')

if __name__=='__main__':main()
