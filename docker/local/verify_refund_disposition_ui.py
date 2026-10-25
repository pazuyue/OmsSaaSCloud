"""Read-only browser acceptance: own cancellation, shared returns, pending receipts and links."""
import copy
import json
import re
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import BASE, login, request
from retest_after_ship_refund_auto import Retest

expect.set_options(timeout=15000)

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'outputs/refund-disposition-ui'


def main():
    OUT.mkdir(exist_ok=True)
    token = login()
    def api(id, suffix=''): return request('/order/refunds/business/' + str(id) + suffix, token=token)['data']
    ids = [85, 97, 99, 101, 104]
    fresh_path = OUT / 'fresh-cancel/result.json'
    if fresh_path.exists(): ids.append(json.loads(fresh_path.read_text('utf-8'))['case']['refunds']['target']['id'])
    before = {id: api(id) for id in ids}
    results = {id: api(id, '/disposition') for id in ids}
    r = Retest(); r.token = token
    cases = [r.data['cases'][key] for key in ['07', '08', '09']]
    stocks = {c['key']: r.snapshot(c) for c in cases}
    mixed = results[99]
    assert len(mixed['cancellations']) == 1 and mixed['cancellations'][0]['canceled_quantity'] == 2, mixed
    assert mixed['cancellations'][0]['receipt_confirmed']
    assert mixed['cancellations'][0]['logical_released_quantity'] is None, 'Old receipts must not invent release amounts'
    assert len(mixed['returns']) == 1 and mixed['returns'][0]['accepted_quantity'] == mixed['returns'][0]['posted_quantity'] == 1
    shared = results[101]['returns']
    assert {row['item_kind'] for row in shared} == {'PURCHASE', 'GIFT'}, shared
    assert all(row['quantity'] == row['accepted_quantity'] == row['posted_quantity'] == 1 for row in shared)
    assert len({d['return_sn'] for row in shared for d in row['documents']}) == 1
    errors, writes, checks = [], [], []
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        ctx = browser.new_context(viewport={'width': 1640, 'height': 1050})
        ctx.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])
        def opened(id, fixture=None, width=1640):
            page = ctx.new_page(); page.set_viewport_size({'width': width, 'height': 1050}); page.set_default_timeout(30000)
            page.on('pageerror', lambda e: errors.append(str(e)))
            page.on('request', lambda q: writes.append(q.url) if '/prod-api/' in q.url and q.method not in ['GET', 'HEAD', 'OPTIONS'] else None)
            if fixture is not None: page.route('**/prod-api/order/refunds/business/' + str(id) + '/disposition', lambda route: route.fulfill(json={'code': 200, 'data': fixture}))
            page.goto(BASE + '/oms-order/businessRefunds?id=' + str(id), wait_until='networkidle')
            dialog = page.get_by_role('dialog', name=re.compile(r'^业务退款详情'))
            box = dialog.locator('.refund-disposition')
            expect(box.get_by_text('本退款商品处置结果', exact=True)).to_be_visible()
            expect(box.get_by_role('button', name='刷新结果')).to_be_enabled()
            expect(box.locator('.el-loading-mask')).not_to_be_visible(timeout=20000)
            expect(box.locator('.result-filter')).to_be_visible(timeout=20000)
            box.get_by_role('button', name='刷新结果').scroll_into_view_if_needed()
            return page, dialog, box
        def screenshot(page, name): page.screenshot(path=str(OUT / name), animations='disabled')

        page, d, box = opened(99)
        cancel = box.locator('.cancellation-results')
        expect(cancel).to_contain_text('2 件'); expect(cancel).to_contain_text('数量未记录 / 待核对')
        ret = box.locator('.disposition-return-results')
        expect(ret).to_contain_text('1 件'); expect(ret).to_contain_text('已退齐并入库')
        screenshot(page, 'mixed-cancel-and-return.png')
        cancel.get_by_role('button', name='查看明细', exact=True).click()
        expect(cancel).to_contain_text('整张原单撤回范围')
        expect(cancel).to_contain_text('原回执未记录数量')
        shipment = mixed['cancellations'][0]['warehouses'][0]['shipment_sn']
        expect(cancel.locator('a').filter(has_text='原发货单').first).to_have_attribute('href', '/oms-order/shipments?number=' + shipment)
        expect(cancel).to_contain_text('本退款销售需求取消')
        cancel.locator('.result-detail').scroll_into_view_if_needed(); screenshot(page, 'cancel-original-receipt.png')
        checks.append('Real mixed case: canceled2 and returned1 remain separate; legacy release unknown; original shipment link uses its number')
        page.close()

        page, d, box = opened(101)
        expect(box.locator('.disposition-return-results .el-table__body-wrapper > table > tbody > tr')).to_have_count(2)
        box.get_by_text('关联赠品退回', exact=True).click()
        expect(box.locator('.disposition-return-results')).to_contain_text('关联赠品')
        expect(box.locator('.disposition-return-results .el-table__body-wrapper > table > tbody > tr')).to_have_count(1)
        box.get_by_role('button', name='查看明细', exact=True).click()
        expect(box).to_contain_text('正品实收 1 件')
        expect(box).to_contain_text('正品已入库 1 件')
        expect(box.locator('.result-documents a').first).to_have_attribute('href', __import__('re').compile(r'^/oms-supplychain/wmsTickets\?sn=THK.*documentId='))
        box.locator('.disposition-return-results').scroll_into_view_if_needed(); screenshot(page, 'gift-shared-return-evidence.png')
        box.get_by_role('button', name='查看包裹', exact=True).click()
        parcel = page.locator('.refund-parcel-drawer')
        expect(parcel).to_be_visible(); expect(parcel).to_contain_text('本退款')
        screenshot(page, 'shared-parcel-link.png')
        parcel.get_by_role('button',name='返回退款单',exact=True).click()
        d.get_by_role('tab',name='本次处理',exact=True).click()
        box.get_by_role('button',name='处理原安排',exact=True).click()
        expect(d.get_by_role('tab',name='退货与入库',exact=True)).to_have_attribute('aria-selected','true')
        expect(d.locator('.plan-groups')).to_contain_text('关联赠品（1行）')
        checks.append('Real common THK: purchase1 + gift1 retain their own quantities; gift filter and parcel link work through read-only requests')
        page.close()

        page, d, box = opened(85)
        ret = box.locator('.disposition-return-results')
        expect(ret.locator('.el-table__body-wrapper > table > tbody > tr')).to_have_count(9)
        expect(ret).to_contain_text('所属 [复合退货验收] 购买kit')
        expect(ret).to_contain_text('所属 [复合退货验收] random')
        expect(ret).to_contain_text('1 套')
        screenshot(page,'real-kit-bag-and-gift-sources.png');page.close()
        checks.append('Retained kit/bag case: nine original source rows preserve sales parent, gift identity and separate 件/套 units')

        partial = copy.deepcopy(results[97])
        row = partial['returns'][0]
        row.update(quantity=2, accepted_quantity=1, posted_quantity=0, remaining_quantity=1, unposted_quantity=1,
                   good_quantity=0, bad_quantity=1, posted_good_quantity=0, posted_bad_quantity=0,
                   pending_review_quantity=1, parcel_pending=True, state='PARTIAL')
        row['receipt_lines'] = [{'return_sn':row['documents'][0]['return_sn'],'batch_code':'TEST-PENDING-BATCH','good_quantity':0,'bad_quantity':1,'posted':0,'recorded_at':'2026-10-24 12:00:00'}]
        stopped = copy.deepcopy(results[99]['cancellations'][0]); stopped.update(key='stopped', state='CANCELED', receipt_confirmed=False, canceled_quantity=None, logical_released_quantity=None)
        partial['cancellations'] = [stopped]
        page, d, box = opened(97, partial)
        expect(box.locator('.cancellation-results')).to_have_count(0)
        box.get_by_text('显示已停止任务（1）', exact=True).click()
        expect(box.locator('.cancellation-results')).to_contain_text('原任务已停止')
        ret = box.locator('.disposition-return-results')
        expect(ret).to_contain_text('未收 1 件'); expect(ret).to_contain_text('已收待入库 1 件')
        expect(ret).to_contain_text('回传待核对 1 件'); expect(ret).to_contain_text('另有整包实收待分配')
        ret.get_by_role('button', name='查看明细', exact=True).click()
        expect(ret).to_contain_text('次品实收 1 件'); expect(ret).to_contain_text('次品已入库 0 件')
        expect(ret).to_contain_text('待入账')
        ret.scroll_into_view_if_needed(); screenshot(page, 'fixture-partial-pending-bad-stock.png')
        checks.append('Browser fixture: stopped task excluded by default, partial receipt, unposted bad goods, review and whole-parcel pending remain distinct')
        page.close()

        waived = copy.deepcopy(partial); waived['cancellations']=[]
        waived['returns'][0].update(quantity=3,accepted_quantity=1,posted_quantity=1,remaining_quantity=0,unposted_quantity=0,waived_quantity=2,pending_review_quantity=0,parcel_pending=False)
        page, d, box = opened(97, waived)
        expect(box).to_contain_text('不再追回 2 件'); expect(box).to_contain_text('含不再追回处理')
        screenshot(page, 'fixture-waived-separate-from-received.png'); page.close()
        checks.append('Browser fixture: waived2 remains separate from received1 and is not counted as stock received')

        if fresh_path.exists():
            id=ids[-1]
            page, d, box = opened(id)
            expect(box.locator('.cancellation-results')).to_contain_text('已确认释放预占 1 件')
            box.locator('.cancellation-results').get_by_role('button',name='查看明细',exact=True).click()
            expect(box).to_contain_text('实际释放预占'); expect(box).not_to_contain_text('原回执未记录数量')
            box.locator('.cancellation-results').scroll_into_view_if_needed(); screenshot(page,'fresh-exact-stock-effects.png')
            checks.append('Fresh automatically reviewed cancellation: original receipt confirms release1, not a derived task quantity')
            page.close()
        page, d, box = opened(99, width=1000)
        expect(box.get_by_role('button', name='刷新结果')).to_be_in_viewport()
        screenshot(page, 'narrow-screen.png'); page.close()
        browser.close()
    for id in ids:
        after = api(id)
        for field in ['state','review_state','review_by','control_version','goods_state','funds_state','events']:
            assert before[id][field] == after[field], (id, field)
    assert stocks == {c['key']:r.snapshot(c) for c in cases}
    assert not errors, errors
    assert not writes, writes
    (OUT/'result.json').write_text(json.dumps({'checks':checks,'browser_writes':writes,'page_errors':errors,'refund_states_unchanged':True,'inventory_unchanged':True,'real_reports':results},ensure_ascii=False,indent=2,default=str),'utf-8')
    print('PASS disposition browser acceptance; no writes or retained refund/stock changes')


if __name__ == '__main__':
    main()
