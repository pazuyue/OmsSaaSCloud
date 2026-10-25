"""Verify ended orders cannot be reviewed; browser checks are read-only."""
import copy
import json
import re
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import BASE, login, request
import verify_after_ship_refund_auto_cases as fixtures

OUT=Path(__file__).resolve().parents[2]/'outputs/order-cancellation'
expect.set_options(timeout=20000)


def main():
    token=login()
    def get(id):return request('/order/orders/business/'+str(id),token=token)['data']
    original=get(151)
    assert original['status']=='VOIDED' and original['can_review'] is False
    # This exact ended order has been checked first; the endpoint must reject without creating approval facts.
    rejected=request('/order/orders/business/approve-batch',{'orders':[{'id':151,'control_version':original['control_version']}],'reason':'验收：已取消订单必须拒绝审核'},token=token)['data'][0]
    assert rejected['success'] is False,rejected
    after=get(151)
    for key in ['control_version','review_status','review_by','review_at','review_events','logs']:
        assert original[key]==after[key],key
    assert request('/order/orders/business/list?tid='+original['order_sn']+'&reviewStatus=PENDING',token=token)['total']==0
    mixed=get(146)
    assert mixed['shipped_quantity']==1 and mixed['cancelled_quantity']==2
    assert mixed['can_review'] is False and mixed['review_required'] is False
    fresh=json.loads((OUT/'fresh/manifest.json').read_text('utf-8'))['cases']['04']
    ids=[151,fresh['order_id']]
    originals={id:get(id) for id in ids}
    errors,writes,checks=[],[],[]
    with sync_playwright() as p:
        browser=p.chromium.launch(headless=True)
        ctx=browser.new_context(viewport={'width':1640,'height':1100})
        ctx.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}])
        def opened(id,fixture=None):
            page=ctx.new_page();page.set_default_timeout(30000)
            page.on('pageerror',lambda e:errors.append(str(e)))
            page.on('request',lambda q:writes.append(q.url) if '/prod-api/' in q.url and q.method not in ['GET','HEAD','OPTIONS'] else None)
            if fixture:page.route('**/prod-api/order/orders/business/'+str(id),lambda route:route.fulfill(json={'code':200,'data':fixture}))
            page.goto(BASE+'/oms-order/business?view=business&orderId='+str(id),wait_until='networkidle')
            panel=page.locator('.el-drawer:visible .order-panel')
            expect(panel.locator('.detail-summary')).to_contain_text(originals[id]['order_sn'])
            return page,panel
        for id in ids:
            page,panel=opened(id)
            expect(panel.locator('.detail-summary')).to_contain_text('已作废')
            expect(panel.locator('.order-detail-footer').get_by_role('button',name='审核通过',exact=True)).to_have_count(0)
            expect(panel.locator('.order-detail-footer').get_by_role('button',name='重新尝试逻辑预占',exact=True)).to_have_count(0)
            panel.get_by_role('tab',name='审核与异常',exact=True).click()
            expect(panel.locator('.review-evidence')).to_contain_text('履约已结束，无需再审核')
            expect(panel.locator('.review-evidence').get_by_role('button',name='人工审核通过',exact=True)).to_have_count(0)
            expect(panel.locator('.review-evidence').get_by_role('button',name='重新判定',exact=True)).to_have_count(0)
            expect(panel).to_contain_text('未发商品已全部取消，预占已释放')
            panel.locator('.review-evidence').scroll_into_view_if_needed()
            page.screenshot(path=str(OUT/('named-order.png' if id==151 else 'fresh-order.png')),animations='disabled')
            checks.append('Real order '+str(id)+': terminal status, no approval/reservation/recheck, original audit history retained')
            if id==151:
                panel.locator('.order-detail-footer').get_by_role('button',name='关闭',exact=True).click()
                number=page.get_by_placeholder('OMS / 平台 / 发货 / 售后 / 运单号',exact=True)
                number.fill(original['order_sn']);number.press('Enter')
                row=page.locator('.order-workspace .el-table__fixed .el-table__fixed-body-wrapper tr').filter(has_text=original['order_sn']).first
                expect(row).to_be_visible();row.locator('.el-checkbox__inner').click()
                page.locator('.order-toolbar').get_by_role('button',name=re.compile(r'批量处理（1）')).click()
                expect(page.locator('.el-dropdown-menu:visible').get_by_text('审核通过',exact=True)).to_have_class(re.compile(r'.*is-disabled.*'))
                checks.append('Real canceled order selected in the list: batch approval disabled')
            page.close()
        partial=copy.deepcopy(original)
        partial.update(status='WAIT_ALLOCATION',business_status='WAIT_REVIEW',inventory_status='RESERVED',review_required=True,can_review=True,can_hold=True,can_resume=False,review_status='PENDING',review_decision='MANUAL_REQUIRED',review_by='',review_at=None,review_reason='退款取消后的剩余2件待核对',demand_quantity=3,cancelled_quantity=1,reserved_quantity=2,shipped_quantity=0,effective_demand_quantity=2,cutoff_state='REPLAN_READY',release_blocks=['尚未审核通过'])
        page,panel=opened(151,partial)
        expect(panel.locator('.order-detail-footer').get_by_role('button',name='审核通过',exact=True)).to_be_visible()
        panel.get_by_role('tab',name='审核与异常',exact=True).click()
        expect(panel.locator('.review-evidence').get_by_role('button',name='人工审核通过',exact=True)).to_be_visible()
        expect(panel.locator('.review-evidence')).to_contain_text('剩余2件待核对')
        page.screenshot(path=str(OUT/'fixture-partial-remaining-review.png'),animations='disabled')
        checks.append('Browser-only partial cancellation fixture: remaining2 can still be reviewed; no review submitted')
        browser.close()
    assert not errors,errors
    assert not writes,writes
    for id in ids:
        latest=get(id)
        for key in ['control_version','review_status','review_by','review_at','review_events','logs']:
            assert latest[key]==originals[id][key],(id,key)
    fixtures.dump(OUT/'ui-result.json',{'pass':True,'checks':checks,'direct_approval_rejected':rejected,'browser_writes':writes,'page_errors':errors,'order_states_and_approval_history_unchanged':True})
    print('PASS cancelled-order browser and API rejection; no browser writes or approval-history changes')


if __name__=='__main__':main()
