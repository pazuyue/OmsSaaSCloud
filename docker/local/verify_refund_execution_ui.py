"""Read real completed refunds; use browser-only fixtures for new pending scope UI.
No refund approval, platform execution or warehouse/inventory command is submitted.
"""
import copy,json,re,time
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_merged_composite_gift_refunds import Acceptance
from verify_goods_workspace_ui import BASE
from test_multi_virtual_shipping import sql
from verify_queue_management import wait_health
OUT=Path(__file__).resolve().parents[2]/'outputs/refund-execution'

def main():
    OUT.mkdir(parents=True,exist_ok=True)
    for service in ['oms-local-system','oms-local-inventory','oms-local-channel','oms-local-order']:wait_health(service)
    a=Acceptance();before={'stock':a.snapshot(),'preceding':a.protected_order()}
    assert json.loads((OUT/'protected-before.json').read_text('utf-8'))==before
    rid=a.c['refunds']['kit']['id'];base='/order/refunds/business/'+str(rid)
    row=a.api(base)
    assert row['platform_interaction']['execution_supported'] is True
    assert row['funds_state']=='SUCCESS' and row['goods_state']=='DONE'
    assert row['fulfillment']['stage']=='SHIPPED'
    policy=a.api('/order/refunds/flow-configs/'+str(row['channel_id']))
    assert int(policy['enabled'])==int(policy['auto_pay'])==int(policy['api_sync']['enabled'])==0
    capability=a.api('/channel/refundPlatform/'+str(row['channel_id'])+'/capability')
    assert int(capability['write_enabled'])==int(capability['payment_enabled'])==0
    queues=a.api('/system/mqQueues')['queues'];wanted=['REFUND_FLOW','REFUND_GOODS','REFUND_PLATFORM','REFUND_API_PULL','REFUND_API_DETAIL']
    expected_modes={x.split('\t')[0]:x.split('\t')[2] for x in json.loads((OUT/'schema.json').read_text('utf-8'))['queues'].splitlines()}
    assert all(q['desired_mode']==expected_modes[q['business_type']] for q in queues)
    assert all(any(q['business_type']==kind for q in queues) for kind in wanted)
    timing=[]
    for _ in range(3):
        start=time.perf_counter();result=a.api('/order/refunds/business?pageSize=50&pageNum=1');timing.append(round((time.perf_counter()-start)*1000,1));assert result['rows']
    writes=[];errors=[];checks=['real completed refund has local fulfillment, platform interactions and separate final funds','merchant automation/payment/API supplement remain disabled','all five new queue types are registered; previous pause modes preserved']
    with sync_playwright() as p:
        browser=p.chromium.launch(headless=True);ctx=browser.new_context(viewport={'width':1650,'height':1180});ctx.add_cookies([{'name':'Admin-Token','value':a.token,'url':BASE}])
        def opened(fixture=None):
            page=ctx.new_page();page.set_default_timeout(30000);page.on('pageerror',lambda error:errors.append(str(error)))
            page.on('request',lambda request:writes.append(request.url) if '/prod-api/' in request.url and request.method not in ['GET','HEAD','OPTIONS'] else None)
            if fixture is not None:page.route('**/prod-api'+base,lambda route:route.fulfill(json={'code':200,'data':fixture}))
            page.goto(BASE+'/oms-order/businessRefunds?id='+str(rid),wait_until='networkidle')
            dialog=page.get_by_role('dialog',name=re.compile('^业务退款详情'))
            expect(dialog.locator('.refund-fulfillment')).to_have_count(1)
            return page,dialog
        page,d=opened();expect(d.locator('.refund-fulfillment')).to_contain_text('全部已出库');d.get_by_role('tab',name='平台处理与资金',exact=True).click();expect(d.locator('.refund-platform-progress')).to_contain_text('平台已退款');expect(d.locator('.platform-actions')).to_contain_text('查询平台最新状态与可用操作');page.screenshot(path=str(OUT/'completed-platform.png'),full_page=True);page.close();checks.append('real browser: fulfillment appears once; confirmed money and platform actions are separate')
        fixture=copy.deepcopy(row);fixture.update(review_state='PENDING',approved_version=None,approved_hash=None,goods_state='WAIT_ACTION',funds_state='NONE',state='PROCESSING',goods_action='MIXED',goods_tasks=[],gift_assessment_required=False,bundles=[],purchase_progress={},gift_progress={},refund_fee='2.00')
        fixture['platform'].update(platform_status='WAIT_SELLER_AGREE',version=10,refund_fee='2.00')
        fixture['progress_context']={'plans':[]};fixture['platform_interaction']={'execution_supported':True,'operations':[],'observations':[]}
        line={'order_line_id':1101,'sku_sn':'UI_SCOPE_A','goods_name':'分仓测试商品','quantity':5,'cancelled_quantity':0,'shipped_quantity':2,'unshipped_quantity':3,'stock_unit':'件','stage':'PART_SHIPPED','sources':[{'shipment_line_id':2201,'shipment_id':3301,'shipment_sn':'XS_UI_FIXTURE','store_code':'V_UI','quantity':2,'shipped_quantity':2,'cancelled_quantity':0,'remaining_quantity':0,'status':'SHIPPED'}]}
        fixture['fulfillment']={'stage':'PART_SHIPPED','uncertain':False,'lines':[line]}
        fixture['lines']=[dict(line,id=1101,platform_oid=fixture['platform_oid'])]
        page,d=opened(fixture);page.route('**/prod-api'+base+'/goods/options',lambda route:route.fulfill(json={'code':200,'data':{'warehouses':[{'code':'V_UI','name':'测试退回仓'}]}}));d.get_by_role('tab',name='审核与来源',exact=True).click();d.get_by_role('button',name='审核退款方案',exact=True).click();editor=page.locator('.scope-editor:visible');expect(editor).to_contain_text('最多 3 件');expect(editor).to_contain_text('已出库 2 件');inputs=editor.locator('.scope-input .el-input-number input');inputs.nth(0).fill('3');inputs.nth(0).press('Tab');inputs.nth(1).fill('2');inputs.nth(1).press('Tab');expect(editor.get_by_placeholder('选择本次退回接收仓')).to_be_visible();page.screenshot(path=str(OUT/'mixed-review.png'),full_page=True);page.close();checks.append('browser fixture: independently confirm 3 unshipped cancellation units and 2 shipped return units; warehouse selector loads')
        fixture.update(review_state='APPROVED',approved_version=10,goods_tasks=[{'id':4401,'action':'CANCEL','order_line_id':1101,'quantity':3,'state':'WAIT_WAREHOUSE','result_message':'原仓库正在核对截单结果','plan_input':{'action':'CANCEL','order_line_id':1101,'quantity':3}}])
        page,d=opened(fixture);expect(d.locator('.refund-progress')).to_contain_text('等待原仓库截单确认');d.get_by_role('tab',name='平台处理与资金',exact=True).click();d.locator('.refund-progress .progress-heading').get_by_role('button',name='查看进度',exact=True).click();expect(d.get_by_role('tab',name='本次处理',exact=True)).to_have_attribute('aria-selected','true');page.screenshot(path=str(OUT/'mixed-progress.png'),full_page=True);page.close();checks.append('browser fixture: current progress names the original warehouse cancellation wait')
        fixture['platform_interaction']['operations']=[{'id':1,'action':'REVIEW','state':'SUCCESS','source_version':10,'fee':'2.00','result_message':'平台退款审核已返回'},{'id':2,'action':'PAY','state':'UNKNOWN','source_version':10,'fee':'2.00','error_code':'isp.timeout','result_message':'先查询原付款结果，不重复付款'}]
        fixture['platform_interaction']['operations'].reverse();page,d=opened(fixture);d.get_by_role('tab',name='平台处理与资金',exact=True).click();expect(d.locator('.refund-platform-progress')).to_contain_text('提交退款付款');expect(d.locator('.refund-platform-progress')).to_contain_text('先查询原付款结果，不重复付款');d.get_by_text('先查询原付款结果，不重复付款',exact=True).scroll_into_view_if_needed();page.screenshot(path=str(OUT/'platform-unknown.png'),full_page=True);page.close();checks.append('browser fixture: successful platform review and unknown payment remain distinct from confirmed money')
        browser.close()
    after={'stock':a.snapshot(),'preceding':a.protected_order()};assert before==after
    assert not writes,writes
    assert not errors,errors
    report={'checks':checks,'real_refund':row['refund_sn'],'browser_fixtures_only':['mixed partial warehouse scope','pending warehouse cancellation','unknown payment UI'],'list_page_50_ms':timing,'writes':writes,'browser_errors':errors,'protected_stock_unchanged':True,'real_platform_payment_tested':False,'queues':[{'type':q['business_type'],'mode':q['desired_mode'],'state':q['effective_state'],'consumers':q['actual_consumers']} for q in queues if q['business_type'] in wanted]}
    (OUT/'acceptance.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8');print('PASS refund execution browser/read-only acceptance:',len(checks),'checks; stocks unchanged; no platform/warehouse writes')
if __name__=='__main__':main()
