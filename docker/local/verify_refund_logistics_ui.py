"""Read-only acceptance. Retained records are read unchanged; edge cases use browser GET fixtures."""
import copy
import json
import re
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import BASE, login, request
from retest_after_ship_refund_auto import Retest

expect.set_options(timeout=20000)
ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'outputs/refund-logistics-ui'


def main():
    OUT.mkdir(exist_ok=True)
    token = login()
    def api(id, suffix=''):
        return request('/order/refunds/business/' + str(id) + suffix, token=token)['data']
    ids = [96, 97, 99, 101]
    before = {id: api(id) for id in ids}
    reports = {id: api(id, '/disposition') for id in ids}
    tracker = Retest(); tracker.token = token
    cases = [tracker.data['cases'][key] for key in ['06', '07', '08', '09']]
    stocks = {c['key']: tracker.snapshot(c) for c in cases}
    baseline_path = OUT/'before-deployment.json'
    if baseline_path.exists():
        baseline = json.loads(baseline_path.read_text('utf-8'))
        assert baseline['stocks'] == stocks, 'inventory changed during rollout'
        for id in ids:
            for key, value in baseline['refunds'][str(id)].items():
                assert before[id][key] == value, (id, key, 'changed during rollout')
    real = reports[96]['logistics']
    assert real['application']['state'] == 'SUCCESS'
    assert not real['reported_outcome'], 'platform request acceptance cannot imply a courier result'
    assert real['platform_mode'] == 'SIMULATION'
    assert len(real['original_packages']) == len(real['returns']) == 1
    original = real['original_packages'][0]
    assert original['express_code'] == 'TEST-SF-XS88efc1a8f4ca46cd9420f314526c974f'
    info = real['returns'][0]['logistics']
    assert info['state'] == 'BOUND' and info['waybill'] == 'AFRET_20261023150323_06'
    assert info['source'] == 'PLATFORM' and info['warehouse_environment'] == 'TEST'
    assert info['time_kind'] == 'PARCEL_REGISTERED'
    assert '仓库已确认实收' in real['returns'][0]['transport_display']
    assert real['returns'][0]['accepted_quantity'] == real['returns'][0]['posted_quantity'] == 1
    assert len(reports[101]['logistics']['returns']) == 2
    assert len({row['logistics']['parcel_id'] for row in reports[101]['logistics']['returns']}) == 1
    errors, writes, checks = [], [], []
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        ctx = browser.new_context(viewport={'width': 1640, 'height': 1080})
        ctx.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])

        def watch(page):
            page.on('pageerror', lambda e: errors.append(str(e)))
            page.on('request', lambda q: writes.append(q.method+' '+q.url) if '/prod-api/' in q.url and q.method not in ['GET', 'HEAD', 'OPTIONS'] else None)

        def opened(id=96, fixture=None, width=1640):
            page = ctx.new_page(); watch(page)
            page.set_viewport_size({'width': width, 'height': 1080})
            if fixture is not None:
                page.route('**/prod-api/order/refunds/business/'+str(id)+'/disposition', lambda route: route.fulfill(json={'code': 200, 'data': fixture}))
            page.goto(BASE+'/oms-order/businessRefunds?id='+str(id), wait_until='networkidle')
            dialog = page.get_by_role('dialog', name=re.compile(r'^业务退款详情'))
            box = dialog.locator('.refund-logistics')
            expect(box).to_be_visible()
            box.scroll_into_view_if_needed()
            return page, dialog, box

        def shot(page, name):
            page.screenshot(path=str(OUT/name), animations='disabled')

        page, d, box = opened()
        expect(box).to_contain_text('平台已受理拦截申请')
        expect(box).to_contain_text('尚未取得快递拦截结果')
        expect(box).to_contain_text('当前平台接入为模拟模式')
        expect(box).to_contain_text('当前仓库环境：TEST')
        expect(box).to_contain_text('平台退款资料')
        expect(box).to_contain_text('AFRET_20261023150323_06')
        expect(box).to_contain_text('物流状态尚未更新，仓库已确认实收')
        expect(box).not_to_contain_text('已登记拦截成功')
        expect(box.get_by_role('button', name='复制退回运单', exact=True)).to_be_visible()
        expect(box.get_by_role('button', name='去登记 / 回告物流结果', exact=True)).to_have_count(0)
        expect(d.locator('.refund-progress')).to_contain_text('本退款处理完成')
        expect(box.locator('.incoming-documents a')).to_have_count(1)
        expect(box.locator('.incoming-documents a')).to_have_attribute('href', re.compile(r'^/oms-supplychain/wmsTickets\?sn=THK.*documentId='))
        box.locator('.logistics-heading').scroll_into_view_if_needed()
        shot(page, 'real-96-platform-logistics-warehouse.png')
        box.get_by_role('tab', name=re.compile('原发货包裹与运单')).click()
        expect(box).to_contain_text(original['express_code'])
        expect(box.get_by_role('button', name='复制原发货运单', exact=True)).to_be_visible()
        expect(box.locator('.logistics-package a')).to_have_attribute('href', '/oms-order/shipments?number='+original['shipment_sn'])
        box.locator('.logistics-package').scroll_into_view_if_needed()
        shot(page, 'real-96-original-package.png')
        box.get_by_role('button', name='查看退回包裹', exact=True).click()
        drawer = page.locator('.refund-parcel-drawer')
        expect(drawer).to_be_visible()
        expect(drawer).to_contain_text('AFRET_20261023150323_06')
        expect(drawer).to_contain_text('物流状态尚未更新，仓库已有实收')
        shot(page, 'real-96-return-parcel.png')
        drawer.get_by_role('button', name='返回退款单', exact=True).click()
        d.get_by_role('tab', name='本次处理', exact=True).click()
        box.get_by_role('button', name='查看拦截申请与回告记录', exact=True).click()
        expect(d.get_by_role('tab', name='平台处理与资金', exact=True)).to_have_attribute('aria-selected', 'true')
        checks.append('Retained 96: platform acceptance separate from unknown courier outcome; confirmed parcel supplies return waybill; warehouse receipt1 and posted1; view actions only navigate')
        page.close()

        page, d, box = opened(101)
        expect(box.locator('.incoming-package')).to_have_count(1)
        expect(box.locator('.incoming-documents a')).to_have_count(1)
        expect(box.locator('.own-logistics-quantities .el-table__body-wrapper > table > tbody > tr')).to_have_count(2)
        expect(box).to_contain_text('购买商品'); expect(box).to_contain_text('关联赠品')
        box.locator('.incoming-package').scroll_into_view_if_needed()
        shot(page, 'real-101-purchase-gift-one-parcel.png'); page.close()
        checks.append('Retained 101: purchase1 and gift1 share one return parcel and one THK; own quantities remain separate')

        page, d, box = opened(99)
        expect(d.locator('.cancellation-results')).to_contain_text('2 件')
        expect(box.locator('.own-logistics-quantities')).to_contain_text('1 件')
        shot(page, 'real-99-cancel-and-return.png'); page.close()
        checks.append('Retained 99: canceled2 and returned1 remain separate')

        for state in ['MISSING', 'PROVIDED', 'UNAVAILABLE']:
            fixture = copy.deepcopy(reports[96]); value = fixture['logistics']; value['goods_done'] = False
            row = value['returns'][0]; row.update(documents=[], accepted_quantity=0, posted_quantity=0, dispatch_state='WAIT_LOGISTICS')
            row['logistics'] = {'state':state,'carrier':'SF' if state=='PROVIDED' else '', 'waybill':'FIXTURE-RETURN' if state=='PROVIDED' else '', 'source':'MANUAL','time_kind':'NOT_RECORDED'}
            row['transport_display'] = '尚未取得退回物流进度' if state!='UNAVAILABLE' else '包裹物流资料暂未读取，仓库实收结果独立保留'
            value['application']['state'] = 'FAILED' if state=='MISSING' else 'UNKNOWN'
            value['application']['message'] = 'FIXTURE 平台申请失败待核对'
            page, d, box = opened(fixture=fixture)
            incoming = box.locator('.incoming-package')
            expect(incoming).not_to_contain_text(original['express_code'])
            expect(incoming).to_contain_text('尚未生成退货入库单')
            if state=='MISSING':
                expect(box).to_contain_text('平台拦截申请失败')
                expect(incoming).to_contain_text('当前下发正在等待退回物流')
                expect(incoming.get_by_role('button', name='去核对退回物流', exact=True)).to_be_visible()
            if state=='PROVIDED':
                expect(incoming).to_contain_text('运单已提供，待关联包裹')
                expect(incoming).to_contain_text('未单独记录')
            if state=='UNAVAILABLE':
                expect(incoming).to_contain_text('包裹物流资料暂未读取')
                expect(incoming).to_contain_text('运单资料暂未读取')
                expect(incoming).not_to_contain_text('退回运单待补充')
                expect(incoming.get_by_role('button', name='去核对退回物流', exact=True)).to_have_count(0)
            incoming.scroll_into_view_if_needed(); shot(page, 'fixture-'+state.lower()+'.png'); page.close()
        checks.append('Browser GET fixtures only: missing, provided and lookup-unavailable logistics are distinct; no outbound waybill substitution, no fake THK and no invented manual time')

        fixture = copy.deepcopy(reports[96]); value = fixture['logistics']
        value['reported_outcome'] = {'logistic_status':'DELIVERY_INTERCEPT_SUCCESS','confirmed':True,'state':'SUCCESS','actor':'fixture seller','recorded_at':'2026-10-24 10:00:00','source':'SELLER_FEEDBACK'}
        value['original_packages'].append(dict(original, id=90001, express_code='FIXTURE-OUTBOUND-2', whole_package_covered=False))
        value['returns'][0]['logistics'].update(source_conflict=True, latest_claimed_waybill='FIXTURE-NEW-RETURN', source='CONFIRMED_PARCEL')
        page, d, box = opened(fixture=fixture)
        expect(box.locator('.intercept-workspace')).to_have_count(1)
        expect(box).to_contain_text('已登记拦截成功')
        expect(box).to_contain_text('商家核实后回告平台；退款级记录')
        expect(box.locator('.incoming-package code')).to_have_text('AFRET_20261023150323_06')
        expect(box).to_contain_text('FIXTURE-NEW-RETURN')
        box.get_by_role('tab', name=re.compile('原发货包裹与运单')).click()
        expect(box.locator('.logistics-package')).to_have_count(2)
        expect(box).to_contain_text('包内商品未全部覆盖')
        shot(page, 'fixture-feedback-multipackage-conflict.png'); page.close()
        checks.append('Browser GET fixture only: reported outcome identifies seller declaration; two original packages do not duplicate refund-level application; changed claim cannot overwrite bound waybill')

        page = ctx.new_page(); watch(page)
        page.goto(BASE+'/oms-order/businessRefunds?number='+before[96]['refund_sn']+'&logisticsState=RESULT_UNKNOWN', wait_until='networkidle')
        expect(page.locator('.refund-table .el-table__empty-text')).to_be_visible()
        page.get_by_role('button', name=re.compile('更多筛选')).click()
        page.get_by_text('包含已完成单据', exact=True).click()
        page.get_by_role('button', name=re.compile('查询')).click()
        expect(page.locator('.refund-table .refund-number').first).to_contain_text(before[96]['refund_sn'])
        expect(page.locator('.refund-table')).to_contain_text('平台已受理拦截申请')
        expect(page.locator('.refund-table')).to_contain_text('尚未取得快递拦截结果')
        shot(page, 'real-list-logistics-completed-opt-in.png'); page.close()
        checks.append('Real list filter: completed96 excluded by default, visible only with completed scope; logistics summary matches detail')

        page, d, box = opened(width=1000)
        expect(box.get_by_role('button', name='查看拦截申请与回告记录', exact=True)).to_be_visible()
        shot(page, 'narrow-screen.png'); page.close()
        browser.close()

    for id in ids:
        after = api(id)
        for field in ['state', 'review_state', 'review_by', 'control_version', 'goods_state', 'funds_state', 'events']:
            assert before[id][field] == after[field], (id, field)
    assert stocks == {c['key']:tracker.snapshot(c) for c in cases}, 'retained stock/quota changed'
    assert not errors, errors
    assert not writes, writes
    (OUT/'result.json').write_text(json.dumps({'checks':checks,'browser_writes':writes,'page_errors':errors,'refund_states_unchanged':True,'inventory_unchanged':True,'real_reports':reports}, ensure_ascii=False, indent=2, default=str), 'utf-8')
    print('PASS refund logistics browser acceptance; retained refunds, payment, inventory and channel quota unchanged; no write requests')


if __name__ == '__main__':
    main()
