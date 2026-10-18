"""Retained, isolated simulation: platform exchange consignment and its central logs.

Business APIs post inventory and fulfillment; only synthetic source messages are
seeded. Real platform transports and real warehouses are never selected.
"""
import json
import re
import urllib.parse
from datetime import datetime, timedelta
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE
from verify_exchange_ownership import Case, ORDER, CHANNEL, INVENTORY
from test_multi_virtual_shipping import sql, quote, wait_for

OUT = Path(__file__).resolve().parents[2] / 'outputs/platform-logs'


def save(name, value):
    (OUT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    token = login()
    try:
        case = Case('notify', token)
        c = case.c
        if not c.get('prepared'):
            c['channel_name'] = '[平台换货回传验收] ' + c['case_id']
            case.save()
        case.prepare()
        case.authorize()
        capability = wait_for(lambda: case.api('/order/exchanges/capability/' + str(c['channel_id']))['data'], lambda cap: cap.get('simulation') is True and cap.get('can_query') is True, 'Trusted simulated authorization not ready', 30)
        assert capability['simulation'] is True and capability['can_query'] is True
        print('Trusted platform simulation and isolated stock ready', flush=True)
        source = case.dispatch_ship(case.allocate()['shipments'][0])
        c['original_shipment_id'] = source['id']
        case.save()
        print('Original synthetic sales shipment completed', flush=True)

        if not c.get('exchange_id'):
            dispute = '999' + c['case_id'] + '01'
            payload = {
                'dispute_id': dispute, 'biz_order_id': c['platform_oid'], 'num': 2, 'status': '1',
                'good_status': '已发货', 'modified': datetime.now().strftime('%Y-%m-%d %H:%M:%S'),
                'time_out': (datetime.now() + timedelta(days=2)).strftime('%Y-%m-%d %H:%M:%S'),
                'bought_sku': '验收原商品 A', 'exchange_sku': '验收换出商品 B',
                'speed_exchange': 0, 'refund_phase': 'aftersale'
            }
            sql(f"INSERT IGNORE INTO channel_exchange_sim(company_code,channel_id,dispute_id,payload,modified) VALUES ('qm',{c['channel_id']},{quote(dispute)},{quote(json.dumps(payload, ensure_ascii=False))},{quote(payload['modified'])})", CHANNEL)
            # Explicit sync keeps the acceptance independent of the next scheduled scan.
            case.api('/order/exchanges/sync', {'channel_id': c['channel_id'], 'dispute_ids': dispute, 'request_key': 'NOTIFY_' + c['case_id']})
            rows = wait_for(lambda: case.api('/order/exchanges?number=' + dispute)['rows'], lambda rows: len(rows) == 1, 'Platform application sync incomplete', 120)
            c.update(exchange_id=rows[0]['id'], exchange_sn=rows[0]['exchange_sn'], dispute_id=dispute)
            case.save()
        e = case.exchange({'id': c['exchange_id']})
        assert e['source_kind'] == 'PLATFORM' and e['capability']['simulation'] is True
        if not e.get('approved_version'):
            e = case.act(e, 'review', {
                'target_sku': c['target_sku'], 'source_shipment_line_id': source['lines'][0]['id'],
                'return_store': c['warehouses'][0]['code'], 'platform_address_id': '991',
                'reason': '[平台回传验收] 核对原发货来源，A 换 B 共2件',
                'recipient_confirmed': True, 'replacement_recipient': e['replacement_recipient']
            })
        if e['platform_status'] == '1' and e['platform_operation'] == 'NONE':
            case.act(e, 'execute', {'operation': 'AGREE', 'reason': '[平台回传验收] 库存满足，同意模拟换货申请'})
        e = wait_for(lambda: case.exchange(e), lambda x: x['platform_status'] in ('2', '3', '12', '13'), 'Platform agreement incomplete', 90)
        if not e['return_sn']:
            case.act(e, 'advance')
        e = wait_for(lambda: case.exchange(e), lambda x: bool(x['return_sn']), 'Return document not prepared')
        if e['return_qc'] != 'PASSED':
            case.returned(e, source['lines'][0]['sku_sn'])
        e = case.exchange(e)
        if e['platform_status'] == '2':
            modified = max(datetime.now() + timedelta(seconds=2), datetime.strptime(e['source_modified'], '%Y-%m-%d %H:%M:%S') + timedelta(seconds=1)).strftime('%Y-%m-%d %H:%M:%S')
            sql(f"UPDATE channel_exchange_sim SET payload=JSON_SET(payload,'$.status','3','$.modified',{quote(modified)}),modified={quote(modified)} WHERE company_code='qm' AND channel_id={c['channel_id']} AND dispute_id={quote(e['dispute_id'])}", CHANNEL)
            e = case.act(e, 'refresh')
        if e['return_qc'] != 'PASSED':
            e = case.act(e, 'qc', {'reason': '[平台回传验收] A 实收2件已记账，核对通过'})
        if e['platform_status'] == '3' and e['platform_operation'] == 'NONE':
            case.act(e, 'execute', {'operation': 'RECEIVED', 'reason': '[平台回传验收] 退回已入库并验收，向模拟平台确认收货'})
        e = wait_for(lambda: case.exchange(e), lambda x: x['platform_status'] in ('12', '13'), 'Platform receipt confirmation incomplete')
        if not e.get('fulfillment_line_id'):
            case.act(e, 'advance')
        e = wait_for(lambda: case.exchange(e), lambda x: bool(x.get('fulfillment_line_id')), 'Replacement line missing')
        assert e['fulfillment_order_id'] == c['order_id'], 'Replacement must stay on original SO'
        if not e['shipments']:
            case.act(e, 'allocate')
        e = case.exchange(e)
        assert len(e['shipments']) == 1
        replacement = case.dispatch_ship(e['shipments'][0])
        e = case.exchange(e)
        assert replacement['business_type'] == 'EXCHANGE' and replacement['shipped_quantity'] == 2
        assert replacement['packages'] and all(p['express_code'].startswith('TEST-') for p in replacement['packages'])
        c.update(return_sn=e['return_sn'], shipment_id=replacement['id'], shipment_sn=replacement['shipment_sn'], waybill=replacement['packages'][0]['express_code'])
        case.save()
        print('Returned A:2, replacement B:2 shipped with one TEST waybill; ready to notify platform', flush=True)

        def stock():
            return sql("SELECT sku_sn,total_stock,available_stock,reserved_stock,version FROM oms_inventory WHERE company_code='QM' AND sku_sn IN (" + quote(c['sku']) + ',' + quote(c['target_sku']) + ') ORDER BY sku_sn', INVENTORY)

        before_stock = stock()
        before_outbound = json.dumps(replacement['outbound_records'], sort_keys=True)
        errors = []
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            context = browser.new_context(viewport={'width': 1520, 'height': 1350})
            context.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])
            page = context.new_page()
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.goto(BASE + '/oms-order/exchanges?exchangeId=' + str(e['id']), wait_until='networkidle')
            drawer = page.locator('.el-drawer:visible')
            if e['platform_status'] == '12' and e['platform_operation'] == 'NONE':
                card = drawer.locator('.platform-status')
                expect(card).to_have_attribute('data-status', 'READY')
                page.screenshot(path=str(OUT / 'notify-before-submit.png'), animations='disabled')
                card.get_by_role('button', name='回传换出物流', exact=True).click()
                dialog = page.get_by_role('dialog', name='回传换出物流', exact=True)
                dialog.locator('textarea').fill('[平台回传验收] B 已实际出库2件，按仓库运单回传并核对结果')
                dialog.get_by_role('button', name='确认回传换出物流', exact=True).click()
            completed = wait_for(lambda: case.exchange(e), lambda x: x['state'] == 'COMPLETED' and x['platform_operation'] == 'NONE', 'Platform consignment not confirmed', 120)
            assert completed['platform_status'] == '13'
            page.goto(BASE + '/oms-order/exchanges?exchangeId=' + str(e['id']), wait_until='networkidle')
            card = page.locator('.el-drawer:visible .platform-status')
            expect(card).to_have_attribute('data-status', 'CONFIRMED')
            expect(card).to_contain_text('模拟平台')
            page.screenshot(path=str(OUT / 'notify-confirmed.png'), animations='disabled')
            card.get_by_role('button', name='平台交互日志', exact=True).click()
            action = page.locator('.el-form-item').filter(has=page.locator('label', has_text=re.compile('^业务动作$')))
            action.locator('.el-select').click()
            page.locator('.el-select-dropdown:visible').get_by_text('回传换出物流', exact=True).click()
            page.locator('.platform-log-center .filters .el-button--primary').click()
            expect(page.locator('.el-table__body-wrapper')).to_contain_text('回传换出物流')
            expect(page.locator('.el-table__body-wrapper')).to_contain_text('模拟')
            expect(page.locator('.platform-log-center .el-loading-mask:visible')).to_have_count(0)
            page.screenshot(path=str(OUT / 'notify-central-log.png'), animations='disabled')
            assert not errors, errors
            browser.close()

        query = urllib.parse.urlencode({'numberType': 'EXCHANGE', 'number': c['exchange_sn'], 'allTime': 'true', 'category': 'EXCHANGE', 'method': 'tmall.exchange.consigngoods'})
        logs = case.api('/channel/platform/log-center?' + query)['data']['rows']
        sends = [r for r in logs if r['entry_kind'] == 'SIMULATION']
        assert len(sends) == 1 and sends[0]['result'] == 'SUCCESS'
        assert all(r['environment'] == 'SIMULATION' for r in logs)
        log = case.api('/channel/platform/log-center/' + str(sends[0]['id']))['data']
        assert any(r['interface_name'] == 'tmall.exchange.get' and r['result'] == 'SUCCESS' for r in log['chain'])
        assert stock() == before_stock
        assert json.dumps(case.shipment(replacement['id'])['outbound_records'], sort_keys=True) == before_outbound
        # Re-checking the completed operation must not issue the consignment again.
        case.act(completed, 'reconcile')
        again = case.api('/channel/platform/log-center?' + query)['data']['rows']
        assert len([r for r in again if r['entry_kind'] == 'SIMULATION']) == 1
        assert sql(f"SELECT COUNT(*) FROM oms_order WHERE company_code='QM' AND channel_id={c['channel_id']} AND platform_tid={quote(c['platform_tid'])}", ORDER) == '1'
        c['completed'] = True
        case.save()
        result = {k: c[k] for k in ['exchange_id', 'exchange_sn', 'dispute_id', 'order_id', 'order_sn', 'platform_tid', 'channel_id', 'return_sn', 'shipment_id', 'shipment_sn', 'waybill']}
        result.update(platform_status='13', state='COMPLETED', simulation=True, real_platform_calls=False,
                      platform_notification_log_id=sends[0]['id'], consignment_calls=1, stock_unchanged_by_notification=True,
                      outbound_unchanged_by_notification=True, one_original_SO=True, browser_errors=[])
        save('notification-acceptance.json', result)
        print(json.dumps(result, ensure_ascii=False), flush=True)
    finally:
        request('/auth/logout', token=token, method='DELETE')


if __name__ == '__main__':
    main()
