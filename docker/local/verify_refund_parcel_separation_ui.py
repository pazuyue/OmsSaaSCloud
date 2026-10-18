"""Read-only UI acceptance against the retained three-TK / one-THK test case.

Exercises source selectors and validation without submitting allocation or cancellation.
"""
import json
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'outputs/refund-shared-thk'


def main():
    case = json.loads((OUT / 'case.json').read_text('utf-8'))
    token = login()

    def api(path):
        return request(path, token=token)['data']

    def snapshot():
        result = []
        for refund in case['refunds']:
            plans = api(f"/order/refunds/business/{refund['id']}/goods")['plans']
            parcel = api(f"/order/refunds/business/{refund['id']}/parcels")[0]
            result.append({
                'plans': [(p['id'], p['accepted_quantity'], p['posted_quantity']) for p in plans],
                'receipts': [(e['id'], e['state'], e['quantity']) for e in parcel['receipts']],
                'thk': parcel['warehouse_document']['return_sn'],
                'cancel': parcel['warehouse_document']['cancel_state']
            })
        return result

    try:
        before = snapshot()
        with sync_playwright() as pw:
            browser = pw.chromium.launch(headless=True)
            context = browser.new_context(viewport={'width': 1600, 'height': 1100})
            context.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])
            page = context.new_page()
            errors = []
            page.on('pageerror', lambda e: errors.append(str(e)))
            for index, refund in enumerate(case['refunds']):
                page.goto(BASE + '/oms-order/businessRefunds?id=' + str(refund['id']), wait_until='networkidle')
                dialog = page.get_by_role('dialog', name='业务退款详情 · 退货退款', exact=True)
                expect(dialog).to_be_visible()
                dialog.get_by_role('button', name='查看本退款货物进度', exact=True).click()
                goods = dialog.locator('.refund-goods')
                expect(goods.locator('.refund-overview')).to_contain_text('待确定（已确认 0 件）')
                expect(goods.locator('.refund-metrics > div').first).to_contain_text('2 件' if index == 0 else '1 件')
                assert goods.locator('.document').count() == 0, 'shared THK must not render standalone per-plan controls'
                expect(page.locator('.refund-parcel-drawer')).not_to_be_visible()
                brief = goods.locator('.parcel-brief')
                expect(brief.locator('.own-scope')).to_contain_text('计划 2 件' if index == 0 else '计划 1 件')
                expect(brief).to_contain_text('包裹数量不等于本退款实收')
                if index == 2:
                    goods.locator('.refund-overview').scroll_into_view_if_needed()
                    page.screenshot(path=str(OUT / 'tk-own-progress.png'), animations='disabled')
                goods.locator('.plan').get_by_role('button', name='查看包裹及处理实收', exact=True).click()
                drawer = page.locator('.refund-parcel-drawer')
                expect(drawer).to_be_visible()
                expect(drawer).to_contain_text(case['thk'])
                expect(drawer).to_contain_text('合包退回 · 3 张退款单')
                expect(drawer.locator('.parcel-metrics > div').nth(0)).to_contain_text('4 件')
                expect(drawer.locator('.parcel-metrics > div').nth(1)).to_contain_text('3 件')
                expect(drawer.locator('.parcel-metrics > div').nth(2)).to_contain_text('3 件')
                expect(drawer).to_contain_text('尚未回传 1 件，仓库尚未确认收货结束')
                expect(drawer).to_contain_text('未提供来源明细行号')
                expect(drawer.locator('.el-tag').filter(has_text='当前TK')).to_have_count(1)
                if index == 2:
                    page.screenshot(path=str(OUT / 'parcel-independent-detail.png'), animations='disabled')
                    drawer.get_by_role('button', name='分配本次实收', exact=True).first.click()
                    review = page.get_by_role('dialog', name='分配本次实收', exact=True)
                    expect(review).to_be_visible()
                    expect(review).to_contain_text('需分配良品 3 件、次品 0 件')
                    review.locator('.el-checkbox').click()
                    expect(review.get_by_role('button', name='确认提交', exact=True)).to_be_disabled()
                    # Select one source, deliberately exceed its remaining quantity, and verify client feedback.
                    review.locator('.allocation .el-select input').first.click()
                    options = page.locator('.el-select-dropdown:visible .el-select-dropdown__item')
                    expect(options).to_have_count(3)
                    options.first.click()
                    review.locator('.allocation .el-input-number input').first.fill('3')
                    review.locator('.allocation .el-input-number input').first.press('Tab')
                    expect(review).to_contain_text('分配数量超过所选退款来源的剩余待收数量')
                    page.screenshot(path=str(OUT / 'allocation-conservation.png'), animations='disabled')
                    review.locator('.allocation .el-input-number input').first.fill('2')
                    review.locator('.allocation .el-input-number input').first.press('Tab')
                    review.get_by_role('button', name='增加退款分配行', exact=True).click()
                    review.locator('.allocation').nth(1).locator('.el-select input').click()
                    page.locator('.el-select-dropdown:visible .el-select-dropdown__item').nth(1).click()
                    review.locator('.allocation').nth(1).locator('.el-input-number input').first.fill('1')
                    review.locator('.allocation').nth(1).locator('.el-input-number input').first.press('Tab')
                    expect(review).to_contain_text('本次良品、次品数量已全部分配')
                    review.locator('textarea').nth(0).fill('[只读验收] 不提交')
                    review.locator('textarea').nth(1).fill('TEST-NO-SUBMIT')
                    expect(review.get_by_role('button', name='确认提交', exact=True)).to_be_enabled()
                    review.get_by_role('button', name='返回', exact=True).click()
                    drawer.get_by_role('button', name='截停共同THK', exact=True).click()
                    scope = page.get_by_role('dialog', name='共同退货入库单处理', exact=True)
                    expect(scope).to_be_visible()
                    assert scope.locator('tbody tr').count() == 3
                    expect(scope.get_by_role('button', name='确认整单截停', exact=True)).to_be_disabled()
                    scope.get_by_role('button', name='返回', exact=True).click()
                drawer.get_by_role('button', name='返回退款单', exact=True).click()
                expect(drawer).not_to_be_visible()
                expect(dialog).to_be_visible()
            # List-level shortcut must open the same independent parcel, not a second business document.
            page.goto(BASE + '/oms-order/businessRefunds?number=' + case['refunds'][2]['refund_sn'], wait_until='networkidle')
            page.get_by_role('button', name='查看退回包裹', exact=True).click()
            expect(page.locator('.refund-parcel-drawer')).to_be_visible()
            expect(page.locator('.refund-parcel-drawer')).to_contain_text(case['thk'])
            assert not errors, errors
            browser.close()
        assert snapshot() == before, 'UI inspection must leave pending receipts and inventory postings unchanged'
        print('PASS three TK scopes, independent parcel drawer, original THK, allocation guards and shared cancellation scope; no business writes')
    finally:
        request('/auth/logout', token=token, method='DELETE')


if __name__ == '__main__':
    main()
