"""Retain one new local TEST case strictly before human approval, for user acceptance.

No review decision is ever submitted by this script or its browser. Reruns never
reset a review which the user has already handled.
"""
import json
from datetime import datetime
from playwright.sync_api import sync_playwright, expect
import test_multi_virtual_shipping as multi
from test_platform_shipping_order import save
from simulate_qimen_delivery import send, mark_simulated_delivery
from verify_goods_workspace_ui import login, request, BASE

OUTPUT = multi.HERE.parents[1] / 'outputs/receipt-review-pending'
MANIFEST = OUTPUT / 'case.json'


def preview(case, credential):
    target = case['warehouses'][1]
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(viewport={'width': 1600, 'height': 1100})
        context.add_cookies([{'name': 'Admin-Token', 'value': credential, 'url': BASE}])
        page = context.new_page()
        errors, writes = [], []
        page.on('pageerror', lambda e: errors.append(str(e)))
        def readonly(route):
            if route.request.method == 'GET':
                route.continue_()
            else:
                writes.append(route.request.url.split('?')[0]); route.abort()
        page.route('**/prod-api/**', readonly)
        page.goto(BASE + '/oms-order/shipments?number=' + target['shipment_sn'], wait_until='networkidle')
        page.get_by_role('button', name=target['shipment_sn'], exact=True).first.click()
        card = page.locator('.el-drawer:visible .receipt-reviews .review-card').first
        card.scroll_into_view_if_needed()
        expect(card).to_contain_text('待人工核对')
        expect(card).to_contain_text('本次未扣账')
        expect(card).to_contain_text(case['warehouses'][0]['shipment_sn'])
        expect(card.get_by_placeholder('填写核对依据和处理原因（必填）')).to_have_value('')
        expect(card.get_by_role('button', name='确认共用运单并补记出库', exact=True)).to_be_disabled()
        expect(card.get_by_role('checkbox')).not_to_be_checked()
        card.get_by_role('button', name='确认共用运单并补记出库', exact=True).scroll_into_view_if_needed()
        page.screenshot(path=str(OUTPUT / 'waiting-for-user.png'))
        assert not errors and not writes, (errors, writes)
        browser.close()
        return {'browser_errors': errors, 'browser_writes': writes, 'reason_empty': True, 'confirmation_unchecked': True}


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    multi.OUTPUT, multi.MANIFEST = OUTPUT, MANIFEST
    if MANIFEST.exists():
        case = json.loads(MANIFEST.read_text(encoding='utf8'))
    else:
        stamp = datetime.now().strftime('%Y%m%d%H%M%S')
        tid = '993' + stamp
        case = {'case_id': stamp, 'created': datetime.now().strftime('%Y-%m-%d %H:%M:%S'), 'sku': 'ACCEPT_MULTI_' + stamp, 'channel_name': '[人工验收] 待核对共用运单 ' + stamp, 'seller': 'ACCEPT_PENDING_' + stamp, 'platform_tid': tid, 'platform_oid': tid + '01', 'warehouses': [{'code': 'PENDING_V1_' + stamp, 'quantity': 6, 'package_count': 2}, {'code': 'PENDING_V2_' + stamp, 'quantity': 4, 'package_count': 1}]}
        save(MANIFEST, case)
    if case.get('retained'):
        raise SystemExit('A retained pending case already exists; inspect its manifest instead of creating or resetting another case')
    credential = login()
    try:
        connection = multi.prepare(case, credential)
        secret = next(c for c in json.loads((multi.HERE / 'wms-simulator-credentials.json').read_text(encoding='utf8')) if c['app_key'] == connection['app_key'] and c['customer_id'] == connection['customer_id'])
        first, second = case['warehouses']
        path = '/order/orders/shipments/' + str(second['shipment_id'])
        if not case.get('first_posted'):
            mark_simulated_delivery(first, connection)
            send(first, connection, secret)
            multi.api('/order/orders/shipments/' + str(first['shipment_id']) + '/reconcile-receipts', credential, post=True)
            multi.wait_for(lambda: multi.api('/order/orders/shipments/' + str(first['shipment_id']), credential)['data'], lambda s: s['shipped_quantity'] == 6, 'First test shipment did not finish posting')
            case['first_posted'] = True
            case['before_rejection'] = multi.stock(case)
            multi.verify_stock(case, case['before_rejection'], 6)
            case['shared_waybill'] = 'TEST-SF-' + first['shipment_sn'] + '-P1'
            second['callback_body'] = second['callback_body'].replace('TEST-SF-' + second['shipment_sn'] + '-P1', case['shared_waybill'])
            save(MANIFEST, case)
        report = multi.api(path + '/receipt-reviews', credential)['data']
        if not report['reviews']:
            mark_simulated_delivery(second, connection)
            try:
                send(second, connection, secret)
            except AssertionError as e:
                assert '待人工核对' in str(e), str(e)
            else:
                raise AssertionError('Shared-waybill callback was not refused')
            report = multi.api(path + '/receipt-reviews', credential)['data']
        assert len(report['reviews']) == 1
        review = report['reviews'][0]
        assert review['state'] == 'WAIT_REVIEW' and review['stock_state'] == 'NOT_POSTED'
        assert not review.get('event_id') and not review.get('operator_name') and not review.get('reason')
        snapshot = multi.stock(case)
        assert snapshot == case['before_rejection'], 'Refusal changed stock'
        business = multi.api('/order/orders/business/' + str(case['order_id']), credential)['data']
        assert business['business_status'] == 'PART_SHIPPED' and business['shipped_quantity'] == 6
        detail = multi.api(path, credential)['data']
        assert detail['shipped_quantity'] == detail['package_count'] == 0 and not detail['outbound_records']
        case['review_id'] = review['id']
        save(MANIFEST, case)
        case['ui_result'] = preview(case, credential)
        # Check once more after the read-only browser: leave the exact decision to the user.
        after = multi.api(path + '/receipt-reviews', credential)['data']['reviews'][0]
        assert after['state'] == 'WAIT_REVIEW' and after['stock_state'] == 'NOT_POSTED'
        assert multi.stock(case) == snapshot
        case.update(retained=True, state='WAIT_REVIEW', stock_state='NOT_POSTED', business_url=BASE + '/oms-order/business?view=business&orderId=' + str(case['order_id']), shipment_url=BASE + '/oms-order/shipments?number=' + second['shipment_sn'])
        save(MANIFEST, case)
        result = {k: case[k] for k in ['order_id', 'order_sn', 'platform_tid', 'sku', 'review_id', 'state', 'stock_state', 'business_url', 'shipment_url', 'ui_result']}
        result.update(pending_shipment_sn=second['shipment_sn'], order_shipped=6, order_quantity=10, pending_quantity=4, remaining_inventory=4, reserved_inventory=4, review_decision_submitted=False, real_platform_notification_sent=False)
        (OUTPUT / 'verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf8')
        print(json.dumps(result, ensure_ascii=False), flush=True)
    finally:
        request('/auth/logout', token=credential, method='DELETE')


if __name__ == '__main__':
    main()
