"""Verify feedback on retained order 47 without approving receipts or changing stock.

Submits one retry of the existing blocked cutoff and retains its genuine audit logs.
"""
import json
import time
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE
from test_multi_virtual_shipping import sql

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'outputs/cutoff-feedback'
ORDER = 47
SHIPMENT = 34


def snapshot(token):
    order = request(f'/order/orders/business/{ORDER}', token=token)['data']
    assert order['order_sn'] == 'SO1cee9a0bb52a4d4d85a1df2ee8e048ec'
    reviews = request(f'/order/orders/shipments/{SHIPMENT}/receipt-reviews', token=token)['data']['reviews']
    return {
        'order': {key: order.get(key) for key in ['id', 'status', 'cutoff_state', 'control_version', 'shipped_quantity', 'cancelled_quantity', 'public_reserved_quantity']},
        'shipments': [{key: row.get(key) for key in ['id', 'status', 'quantity', 'shipped_quantity', 'cancelled_quantity', 'control_version']} for row in order['shipments']],
        'reviews': [{key: row.get(key) for key in ['id', 'state', 'control_version', 'event_id', 'decision', 'stock_state']} for row in reviews],
        'stock': sql("SELECT sku_sn,total_stock,available_stock,reserved_stock FROM oms_inventory WHERE company_code='QM' AND sku_sn='CF_202610151644494'", 'qm_oms_saas_inventory'),
        'warehouses': sql("SELECT store_code,zp_actual_number,zp_available_number,zp_lock_number FROM wms_inventory WHERE company_code='QM' AND sku_sn='CF_202610151644494' ORDER BY store_code", 'qm_oms_saas_inventory'),
    }


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    token = login()
    try:
        before = snapshot(token)
        assert before['reviews'][0]['state'] == 'WAIT_REVIEW'
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            context = browser.new_context(viewport={'width': 1600, 'height': 1100})
            context.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])
            page = context.new_page()
            page.set_default_timeout(25000)
            errors = []
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.goto(BASE + f'/oms-order/business?view=business&orderId={ORDER}', wait_until='networkidle')
            page.get_by_role('button', name='截单 / 查看处理结果', exact=True).first.click()
            dialog = page.get_by_role('dialog', name='订单截单与撤回', exact=True)
            expect(dialog.get_by_text('已截停 4 件，剩余 6 件待处理', exact=False)).to_be_visible()
            expect(dialog.locator('.cutoff-footer').get_by_role('button', name='处理回传异常', exact=True)).to_be_visible()
            with page.expect_response(lambda response: response.url.endswith(f'/{ORDER}/cutoff/retry') and response.request.method == 'POST') as result:
                dialog.get_by_role('button', name='重新核对截单', exact=True).click()
            response = result.value.json()
            assert response['code'] == 200, response.get('msg')
            submitted = response['data']
            expect(dialog.get_by_text('本次核对后仍未完成：', exact=False)).to_be_visible()
            report = request(f'/order/orders/business/{ORDER}/cutoff', token=token)['data']['operations'][0]
            assert report['id'] == submitted['cutoff_id'] and report['checked_version'] >= submitted['check_version']
            expect(dialog.get_by_text('最近完成核对：', exact=False)).to_be_visible()
            page.screenshot(path=str(OUT / 'blocked-result-desktop.png'), full_page=True)
            page.set_viewport_size({'width': 390, 'height': 844})
            box = dialog.bounding_box()
            assert box['y'] >= 0 and box['y'] + box['height'] <= 844, box
            page.screenshot(path=str(OUT / 'blocked-result-mobile.png'), full_page=True)
            page.set_viewport_size({'width': 1600, 'height': 1100})
            dialog.locator('.cutoff-footer').get_by_role('button', name='处理回传异常', exact=True).click()
            expect(dialog).not_to_be_visible()
            # Element UI drawers reuse their title ID; scope by the visible review content.
            shipment = page.locator('.el-drawer:visible').filter(has=page.locator('.receipt-reviews'))
            reviews = shipment.locator('.receipt-reviews')
            expect(reviews.get_by_text('不同包裹使用了相同快递及运单号', exact=True)).to_be_visible()
            expect(reviews.get_by_role('button', name='确认共用运单并补记出库', exact=True)).to_be_visible()
            page.screenshot(path=str(OUT / 'review-navigation.png'), full_page=True)
            assert not errors, errors
            browser.close()
        after = snapshot(token)
        assert before == after, 'Business quantities, stock or review decisions changed'
        logs = sql("SELECT action,result,message,operator_name FROM oms_order_shipment_log WHERE company_code='QM' AND shipment_id=34 AND action LIKE 'CUTOFF%' ORDER BY id", 'qm_oms_saas_order')
        assert 'CUTOFF_RETRY' in logs and 'CUTOFF_BLOCKED' in logs
        evidence = {'order_id': ORDER, 'shipment_id': SHIPMENT, 'submitted': submitted, 'checked_version': report['checked_version'], 'last_checked_at': report['last_checked_at'], 'business_and_stock_unchanged': True, 'snapshot': after, 'shipment_cutoff_logs': logs, 'browser_errors': errors, 'verified_at': time.strftime('%Y-%m-%d %H:%M:%S')}
        (OUT / 'verification.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({key: evidence[key] for key in ['submitted', 'checked_version', 'last_checked_at', 'business_and_stock_unchanged', 'browser_errors']}, ensure_ascii=True))
    finally:
        request('/auth/logout', token=token, method='DELETE')


if __name__ == '__main__':
    main()
