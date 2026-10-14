"""Verify the retained pending case without submitting any human decision."""
import json
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE
import test_multi_virtual_shipping as multi

OUTPUT = multi.HERE.parents[1] / 'outputs/receipt-review-pending'


def main():
    case = json.loads((OUTPUT / 'case.json').read_text(encoding='utf8'))
    target = case['warehouses'][1]
    path = '/order/orders/shipments/' + str(target['shipment_id'])
    credential = login()
    try:
        before = multi.stock(case)
        report = request(path + '/receipt-reviews', token=credential)['data']['reviews'][0]
        assert report['state'] == 'WAIT_REVIEW' and report['stock_state'] == 'NOT_POSTED'
        business = request('/order/orders/business/' + str(case['order_id']), token=credential)['data']
        assert business['receipt_review_pending_count'] == 1 and len(business['receipt_review_shipments']) == 1
        issue_report = request('/order/orders/business/' + str(case['order_id']) + '/issues', token=credential)['data']
        assert issue_report['receipt_review_pending_count'] == 1
        row = next(s for s in business['shipments'] if s['id'] == target['shipment_id'])
        assert row['receipt_review_pending_count'] == 1 and not row['receipt_review_lookup_failed']
        detail = request(path, token=credential)['data']
        assert detail['receipt_review_pending_count'] == 1 and detail['shipped_quantity'] == 0 and detail['package_count'] == 0
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            context = browser.new_context(viewport={'width': 1600, 'height': 1100})
            context.add_cookies([{'name': 'Admin-Token', 'value': credential, 'url': BASE}])
            page = context.new_page()
            errors, writes = [], []
            page.on('pageerror', lambda e: errors.append(str(e)))
            def guarded(route):
                req = route.request
                if req.method == 'GET' or (req.method == 'POST' and req.url.split('?')[0].endswith(path + '/reconcile-receipts')):
                    route.continue_()
                else:
                    writes.append(req.url.split('?')[0]); route.abort()
            page.route('**/prod-api/**', guarded)
            page.goto(BASE + '/oms-order/business?view=business', wait_until='networkidle')
            main_row = page.locator('.el-table__body-wrapper tr').filter(has_text=case['order_sn']).first
            expect(main_row).to_contain_text('仓库回传待核对 1 条')
            expect(main_row).not_to_contain_text('无当前问题')
            main_row.scroll_into_view_if_needed()
            page.screenshot(path=str(OUTPUT / 'business-main-list-review.png'))
            main_row.get_by_role('button', name='处理回传异常', exact=True).click()
            expect(page.locator('.el-drawer:visible .receipt-reviews')).to_contain_text('本次未扣账')
            page.locator('.el-drawer:visible .el-drawer__close-btn').last.click()
            main_row.get_by_role('button', name='查看问题记录', exact=True).click()
            issue_dialog = page.locator('.order-issues-dialog .el-dialog:visible')
            expect(issue_dialog).to_contain_text('仓库回传待核对 1 条')
            expect(issue_dialog).not_to_contain_text('暂无当前问题')
            issue_dialog.get_by_role('button', name='处理回传异常', exact=True).click()
            expect(page.locator('.el-drawer:visible .receipt-reviews')).to_contain_text('本次未扣账')
            page.locator('.el-drawer:visible .el-drawer__close-btn').last.click()
            issue_dialog.get_by_role('button', name='关闭', exact=True).click()
            page.goto(case['business_url'], wait_until='networkidle')
            drawer = page.locator('.el-drawer:visible')
            overview = drawer.locator('.order-panel > .order-receipt-review')
            expect(overview).to_contain_text('仓库回传待核对 1 条')
            expect(drawer.locator('.inventory-issues-summary')).to_contain_text('仓库回传待核对 1 条')
            expect(drawer.locator('.inventory-issues-summary')).not_to_contain_text('无当前问题')
            page.screenshot(path=str(OUTPUT / 'business-overview-review.png'))
            overview.get_by_role('button', name='处理回传异常', exact=True).click()
            expect(page.locator('.el-drawer:visible .receipt-reviews').last).to_contain_text('待人工核对')
            page.locator('.el-drawer:visible .el-drawer__close-btn').last.click()
            drawer.get_by_role('tab', name='发货单', exact=True).click()
            group = drawer.locator('.order-warehouse-group').filter(has_text=target['code'])
            expect(group).to_contain_text('待人工核对')
            expect(group).to_contain_text('回传待核对，包裹未接纳')
            expect(group).to_contain_text('本次未扣账')
            group.scroll_into_view_if_needed()
            page.screenshot(path=str(OUTPUT / 'business-pending-entry.png'))
            group.get_by_role('button', name='处理回传异常', exact=True).click()
            review = page.locator('.el-drawer:visible .receipt-reviews').last
            expect(review).to_contain_text('待人工核对')
            expect(review.get_by_placeholder('填写核对依据和处理原因（必填）')).to_have_value('')
            expect(review.get_by_role('checkbox')).not_to_be_checked()
            expect(review.get_by_role('button', name='确认共用运单并补记出库', exact=True)).to_be_disabled()
            expect(review).to_be_in_viewport()
            page.wait_for_function("""() => { const panels = [...document.querySelectorAll('.receipt-reviews')]; const heading = panels[panels.length-1].querySelector('h3'); const box = heading.getBoundingClientRect(); return box.top >= 0 && box.top < 160 }""")
            page.screenshot(path=str(OUTPUT / 'direct-review-entry.png'))
            shipment = page.locator('.el-drawer:visible').last
            shipment.get_by_role('button', name='核对出库回传', exact=True).click()
            expect(shipment.locator('.outbound-history > .el-alert')).to_contain_text('待人工核对')
            expect(shipment.locator('.outbound-history > .el-alert')).not_to_contain_text('暂无')
            expect(review.get_by_placeholder('填写核对依据和处理原因（必填）')).to_have_value('')
            page.goto(case['shipment_url'], wait_until='networkidle')
            expect(page.locator('.el-table__body-wrapper').first).to_contain_text('待人工核对')
            page.get_by_role('button', name='处理回传异常', exact=True).last.click()
            expect(page.locator('.receipt-reviews')).to_contain_text('本次未扣账')
            assert not errors and not writes, (errors, writes)
            browser.close()
        after = request(path + '/receipt-reviews', token=credential)['data']['reviews'][0]
        assert after['state'] == 'WAIT_REVIEW' and after['stock_state'] == 'NOT_POSTED'
        assert not after.get('event_id') and not after.get('reason') and not after.get('operator_name')
        assert multi.stock(case) == before, 'Pending acceptance inventory changed'
        detail = request(path, token=credential)['data']
        assert detail['shipped_quantity'] == detail['package_count'] == 0 and not detail['outbound_records']
        result = {'business_main_list': True, 'business_overview': True, 'product_summary': True, 'issue_dialog': True, 'business_entry': True, 'shipment_list_entry': True, 'direct_scroll': True, 'reconcile_reports_wait_review': True, 'browser_errors': errors, 'unexpected_writes': writes, 'review_state': after['state'], 'stock_state': after['stock_state'], 'pending_quantity': 4, 'stock_unchanged': True, 'decision_submitted': False}
        (OUTPUT / 'ui-fix-verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf8')
        print('PASS pending review UI and reconcile: original 4 units unchanged; no decision submitted')
    finally:
        request('/auth/logout', token=credential, method='DELETE')


if __name__ == '__main__':
    main()
