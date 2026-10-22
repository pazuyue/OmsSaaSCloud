"""New local-only document fixtures, normal conversion, browser QA and stock checks.

Only this script's newly owned goods/shop/orders are changed. No scheduler or
real platform writes. All test order reservations are released by normal void.
"""
import copy
import hashlib
import json
from datetime import datetime
from decimal import Decimal
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE
from test_multi_virtual_shipping import sql, quote, wait_for
import verify_allocation_rules_v2 as fixture

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'outputs/order-documents'
DB = 'qm_oms_saas_order'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    token = login()
    cases = []

    def api(path, body=None):
        result = request(path, payload=body, token=token, method='POST' if body is not None else 'GET')
        return result.get('data', result)

    def seed(case, credential):
        if sql("SELECT id FROM oms_platform_order WHERE company_code='QM' AND channel_id=" + str(case['channel_id']) + ' AND platform_tid=' + quote(case['platform_tid']), DB):
            return
        mode = case['mode']
        trade = {'tid': case['platform_tid'], 'status': 'WAIT_SELLER_SEND_GOODS', 'type': 'fixed', 'seller_nick': case['seller'],
                 'created': case['created'], 'modified': case['created'], 'pay_time': case['created'], 'post_fee': '10.00', 'discount_fee': '20.00', 'adjust_fee': '0.00',
                 'payment': '367.00', 'total_fee': '367.00', 'buyer_message': '请分开包装，核对赠品', 'seller_memo': '金额信息验收，请核对规格',
                 'receiver_name': '单据信息验收', 'receiver_mobile': 'TEST-MASKED', 'receiver_state': '北京市', 'receiver_address': '本地验收地址，无实际配送',
                 'invoice_name': '验收测试抬头', 'invoice_tax_no': 'TEST-TAX', 'orders': {'order': [
                     {'oid': case['platform_oid'], 'outer_sku_id': case['sku'], 'title': '[金额验收] 商品A', 'num': 2, 'price': '100.00', 'discount_fee': '10.00', 'part_mjz_discount': '5.00', 'adjust_fee': '0.00', 'payment': '190.00', 'divide_order_fee': '185.00'},
                     {'oid': case['platform_tid'] + '02', 'outer_sku_id': case['sku'], 'title': '[金额验收] 商品B', 'num': 1, 'price': '200.00', 'discount_fee': '10.00', 'part_mjz_discount': '15.00', 'adjust_fee': '-3.00', 'payment': '187.00', 'divide_order_fee': '172.00'}
                 ]}}
        if mode != 'multi':
            trade['orders']['order'] = trade['orders']['order'][:1]
            line = trade['orders']['order'][0]
            line.update(num=1, price='100.00', payment='95.00', divide_order_fee='95.00')
            trade.update(payment='95.00', total_fee='95.00', discount_fee='5.00')
        if mode == 'missing':
            trade.pop('post_fee'); trade.pop('discount_fee'); trade['orders']['order'][0].pop('price')
        trade['promotion_details'] = {'promotion_detail': [{'id': case['platform_tid'], 'promotion_id': 'TEST-ORDER-COUPON', 'promotion_name': '订单优惠券', 'discount_fee': trade.get('discount_fee', '5.00')}]}
        payload = json.dumps({'trade_fullinfo_get_response': {'trade': trade}}, ensure_ascii=False)
        digest = hashlib.sha256(payload.encode()).hexdigest()
        sql("INSERT INTO oms_platform_order(company_code,channel_id,platform_tid,platform_status,trade_type,seller_nick,platform_created,platform_modified,source_modified,payload_hash,raw_payload,conversion_status) VALUES ('QM'," + str(case['channel_id']) + ',' + quote(case['platform_tid']) + ",'WAIT_SELLER_SEND_GOODS','fixed'," + quote(case['seller']) + ',' + quote(case['created']) + ',' + quote(case['created']) + ',' + quote(case['created']) + ',' + quote(digest) + ',' + quote(payload) + ",'WAITING')", DB)

    try:
        path = OUT / 'multi-case.json'
        if path.exists():
            main_case = json.loads(path.read_text(encoding='utf-8'))
        else:
            stamp = datetime.now().strftime('%Y%m%d%H%M%S'); tid = '996' + stamp
            main_case = {'case_id': stamp, 'created': datetime.now().strftime('%Y-%m-%d %H:%M:%S'), 'sku': 'DOC_' + stamp, 'seller': 'DOC_' + stamp,
                         'channel_name': '[单据信息验收] ' + stamp, 'platform_tid': tid, 'platform_oid': tid + '01', 'mode': 'multi',
                         'warehouses': [{'code': 'DOC_V_' + stamp, 'quantity': 10, 'package_count': 0}]}
        fixture.MANIFEST = path
        fixture.prepare_case(main_case, token, approve=False, before_conversion=seed, order_quantity=3)
        cases.append(main_case)
        for index, mode in enumerate(['single', 'missing'], 1):
            path = OUT / (mode + '-case.json')
            if path.exists():
                case = json.loads(path.read_text(encoding='utf-8'))
            else:
                case = copy.deepcopy(main_case)
                for key in ['platform_id', 'order_id', 'order_sn']:
                    case.pop(key, None)
                case.update(mode=mode, platform_tid=str(int(main_case['platform_tid']) + index))
                case['platform_oid'] = case['platform_tid'] + '01'
            fixture.MANIFEST = path
            fixture.prepare_case(case, token, approve=False, before_conversion=seed, order_quantity=1)
            cases.append(case)
        multi, single, missing = [api('/order/orders/business/' + str(c['order_id'])) for c in cases]
        d = multi['document']; assert d['sales_amount'] == '400.00' and d['discount_total'] == '40.00' and d['buyer_freight'] == '10.00'
        assert d['goods_payment'] == '357.00' and d['payment'] == '367.00' and d['adjustment'] == '-3.00' and d['check_state'] == 'MATCHED', d
        assert [line['discount_total'] for line in d['lines']] == ['15.00', '25.00']
        assert single['document']['goods_payment'] == '85.00' and Decimal(str(single['lines'][0]['payment'])) == Decimal('85.00')
        assert single['document']['check_state'] == 'MATCHED'
        assert missing['document']['buyer_freight'] is None and missing['document']['sales_amount'] is None and missing['document']['check_state'] == 'INCOMPLETE'
        platform = api('/order/orders/platform/' + str(main_case['platform_id']))
        assert platform['document']['buyer_freight'] == d['buyer_freight'] and platform['document']['discount_total'] == d['discount_total']
        before = fixture.stock(main_case)
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            context = browser.new_context(viewport={'width': 1700, 'height': 1100})
            context.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])
            page = context.new_page(); page.set_default_timeout(25000)
            errors = []; page.on('pageerror', lambda error: errors.append(str(error)))

            def open_order(case):
                page.goto(BASE + '/oms-order/business?view=business&orderId=' + str(case['order_id']), wait_until='networkidle')
                expect(page.locator('.order-amount-summary:visible')).to_be_visible()

            open_order(main_case)
            expect(page.locator('.detail-payment:visible')).to_contain_text('¥367.00')
            expect(page.locator('.order-amount-summary:visible')).to_contain_text('¥400.00')
            expect(page.locator('.order-amount-summary:visible')).to_contain_text('¥40.00')
            expect(page.locator('.order-amount-summary:visible')).to_contain_text('¥10.00')
            table = page.locator('.sales-products-table:visible')
            expect(table).to_contain_text('¥185.00'); expect(table).to_contain_text('¥172.00')
            table.locator('.el-table__expand-icon').first.click()
            expect(page.locator('.sale-amount-details:visible')).to_contain_text('整单优惠分摊')
            page.screenshot(path=str(OUT / 'sales-amounts.png'), animations='disabled')
            page.get_by_role('tab', name='订单资料', exact=True).click()
            info = page.locator('.order-document-info:visible')
            expect(info).to_contain_text('请分开包装，核对赠品'); expect(info).to_contain_text(main_case['created'])
            info.get_by_text('优惠明细与金额核对', exact=True).click(); expect(info).to_contain_text('订单优惠券')
            note = '[单据信息验收] 内部备注与平台备注分别保存'
            if not any(n['message'] == note for n in multi['internal_notes']):
                info.get_by_placeholder('填写内部处理说明，与平台备注分别保存').fill(note)
                info.get_by_role('button', name='添加备注', exact=True).click()
                expect(info).to_contain_text(note)
            page.screenshot(path=str(OUT / 'document-info.png'), animations='disabled')
            open_order(cases[1]); expect(page.locator('.detail-payment:visible')).to_contain_text('¥95.00')
            expect(page.locator('.sales-products-table:visible')).to_contain_text('¥85.00')
            page.screenshot(path=str(OUT / 'single-with-freight.png'), animations='disabled')
            open_order(cases[2]); expect(page.locator('.order-amount-summary:visible')).to_contain_text('金额资料未齐')
            expect(page.locator('.order-amount-summary:visible')).to_contain_text('未提供')
            page.screenshot(path=str(OUT / 'missing-fields.png'), animations='disabled')
            assert not errors, errors
            browser.close()
        assert fixture.stock(main_case) == before, 'Read-only details and notes must not mutate stock'
        (OUT / 'multi-order.json').write_text(json.dumps(api('/order/orders/business/' + str(main_case['order_id'])), ensure_ascii=False, indent=2), encoding='utf-8')
        for case in cases:
            order = api('/order/orders/business/' + str(case['order_id']))
            if order['status'] != 'VOIDED':
                api('/order/orders/business/void-batch', {'orders': [{'id': order['id'], 'control_version': order['control_version']}], 'confirm_local_void': 1, 'reason': '[单据信息验收] 完成验收，释放本次测试占用'})
                wait_for(lambda: api('/order/orders/business/' + str(case['order_id'])), lambda o: o['status'] == 'VOIDED', 'Owned test reservation was not released')
        for case in cases:
            case['completed'] = True
            (OUT / (case['mode'] + '-case.json')).write_text(json.dumps(case, ensure_ascii=False, indent=2), encoding='utf-8')
        final = fixture.stock(main_case)
        assert final['company'][0]['total'] == 10 and final['company'][0]['available'] == 10 and final['company'][0]['reserved'] == 0, final
        report = {'orders': [{'id': c['order_id'], 'number': c['order_sn'], 'mode': c['mode']} for c in cases], 'platform_business_amounts_equal': True,
                  'multi_discount_not_duplicated': True, 'single_freight_excluded_from_goods': True, 'missing_not_zero': True,
                  'internal_note_verified': True, 'stock_unchanged_by_ui': True, 'owned_test_orders_voided': True, 'final_stock': final, 'browser_errors': errors}
        (OUT / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print('PASS order documents:', json.dumps({k: v for k, v in report.items() if k != 'final_stock'}, ensure_ascii=False))
    finally:
        request('/auth/logout', token=token, method='DELETE')


if __name__ == '__main__':
    main()
