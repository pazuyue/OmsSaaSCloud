"""Retained local JDP intake acceptance. No real platform refund requests.

Only the isolated refund_acceptance_source database receives synthetic JDP rows.
Business APIs convert/review; stock and outbound use the existing TEST WMS path.
"""
import json
import sys
import urllib.parse
from datetime import datetime, timedelta
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE
from verify_exchange_ownership import Case
from test_multi_virtual_shipping import sql, quote, wait_for, stock
import verify_allocation_rules_v2 as fixture

HERE = Path(__file__).resolve().parent
OUT = HERE.parents[1] / 'outputs/refunds'
SOURCE = 'refund_acceptance_source'


def save(value):
    (OUT / 'acceptance.json').write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    token = login()
    try:
        def api(path, body=None):
            return request(path, token=token, payload=body, method='POST' if body is not None else 'GET')
        def ready():
            try:
                return api('/order/refunds/catalog')['data']
            except AssertionError:
                return None
        wait_for(ready, lambda r: isinstance(r, list), 'Refund service not ready', 90)
        case = Case('refund', token)
        c = case.c
        if not c.get('prepared'):
            c['channel_name'] = '[仅退款验收] 退差价与拒收 ' + c['case_id']
            case.save()
            fixture.MANIFEST = case.path
            fixture.prepare_case(c, token)
            c['prepared'] = True
            case.save()
        # Ship the original through the existing TEST warehouse. Subsequent refunds must not add stock.
        source_shipment = case.dispatch_ship(case.allocate()['shipments'][0]) if not c.get('original_shipped') else case.shipment(c['original_shipment_id'])
        c.update(original_shipped=True, original_shipment_id=source_shipment['id'])
        case.save()
        assert source_shipment['shipped_quantity'] == 10
        sql('CREATE DATABASE IF NOT EXISTS ' + SOURCE + ' CHARACTER SET utf8mb4', 'ry-cloud')
        sql('CREATE TABLE IF NOT EXISTS jdp_tb_trade(tid DECIMAL(20,0) PRIMARY KEY,status VARCHAR(64),type VARCHAR(64),seller_nick VARCHAR(200),created DATETIME,modified DATETIME,jdp_modified DATETIME,jdp_response MEDIUMTEXT)', SOURCE)
        sql('CREATE TABLE IF NOT EXISTS jdp_tb_refund(refund_id DECIMAL(20,0) PRIMARY KEY,tid DECIMAL(20,0),oid DECIMAL(20,0),status VARCHAR(64),seller_nick VARCHAR(200),created DATETIME,modified DATETIME,jdp_modified DATETIME,jdp_response MEDIUMTEXT,KEY seller_cursor(seller_nick,jdp_modified,refund_id))', SOURCE)
        source_name = '[仅退款验收] 本地JDP ' + c['case_id']
        sources = api('/order/orders/sources')['data']
        configured = next((s for s in sources if s['source_name'] == source_name), None)
        if not configured:
            env = dict(line.split('=', 1) for line in (HERE / '.env').read_text().splitlines() if '=' in line and not line.startswith('#'))
            result = api('/order/orders/sources', {'source_name': source_name, 'host': 'mysql', 'port': 3306, 'database_name': SOURCE, 'username': 'root', 'password': env['MYSQL_ROOT_PASSWORD'], 'enabled': 1})
            assert result['code'] == 200, result.get('msg')
            key = next(s['source_key'] for s in api('/order/orders/sources')['data'] if s['source_name'] == source_name)
        else:
            key = configured['source_key']
        configs = api('/order/refunds/configs')['data']['configs']
        config = next((r for r in configs if r['channel_id'] == c['channel_id']), None)
        if not config:
            api('/order/refunds/configs', {'channel_id': c['channel_id'], 'source_key': key, 'seller_nick': c['seller'], 'initial_time': c['created'], 'revision': 0, 'enabled': 0, 'auto_convert': 0, 'interval_seconds': 30})
        initial_cursor = next(r for r in api('/order/refunds/configs')['data']['configs'] if r['channel_id'] == c['channel_id'])['cursor_time']
        before = stock(c)

        def seed(index, fee, status, version=1):
            rid = '998' + c['case_id'] + str(index).zfill(2)
            modified = (datetime.strptime(c['created'], '%Y-%m-%d %H:%M:%S') + timedelta(seconds=version)).strftime('%Y-%m-%d %H:%M:%S')
            p = {'refund_id': rid, 'tid': c['platform_tid'], 'oid': c['platform_oid'], 'seller_nick': c['seller'], 'status': status, 'refund_fee': fee, 'num': 10, 'has_good_return': False, 'dispute_type': 'REFUND', 'refund_phase': 'aftersale', 'refund_version': str(version), 'reason': '[验收] 退差价' if index == 1 else '[验收] 买家拒收，平台已退款，货物待退回', 'modified': modified}
            values = [quote(v) for v in [rid, c['platform_tid'], c['platform_oid'], status, c['seller'], c['created'], modified, modified, json.dumps({'refund_get_response': {'refund': p}}, ensure_ascii=False)]]
            sql('INSERT INTO jdp_tb_refund(refund_id,tid,oid,status,seller_nick,created,modified,jdp_modified,jdp_response) VALUES (' + ','.join(values) + ') ON DUPLICATE KEY UPDATE status=VALUES(status),modified=VALUES(modified),jdp_modified=VALUES(jdp_modified),jdp_response=VALUES(jdp_response)', SOURCE)
            return rid

        price_id, refusal_id = seed(1, '2.00', 'WAIT_SELLER_AGREE'), seed(2, '8.00', 'SUCCESS')
        missing_id = '998' + c['case_id'] + '99'
        task = api('/order/refunds/sync', {'channel_id': c['channel_id'], 'mode': 'IDS', 'refund_ids': [price_id, refusal_id, missing_id], 'request_key': 'REFUND_ACCEPT_' + c['case_id']})['data']
        task = wait_for(lambda: api('/order/refunds/tasks/' + str(task['id']))['data'], lambda t: t['state'] == 'COMPLETED', 'JDP refund task incomplete', 150)
        assert len(task['items']) == 3 and next(r for r in task['items'] if r['refund_id'] == missing_id)['result'] == 'NOT_FOUND'
        rows = api('/order/refunds/platform?' + urllib.parse.urlencode({'channelId': c['channel_id']}))['data']['rows']
        assert len(rows) == 2
        if not c.get('refunds'):
            assert all(r['business_refund_id'] is None for r in rows), 'Manual transfer setting ignored'
        evidence = []
        for index, rid in enumerate([price_id, refusal_id], 1):
            p = next(r for r in rows if r['refund_id'] == rid)
            result = api('/order/refunds/platform/' + str(p['id']) + '/convert', {})['data']
            assert result['state'] == 'SUCCESS'
            b = api('/order/refunds/business/' + str(result['id']))['data']
            assert api('/order/refunds/platform/' + str(p['id']) + '/convert', {})['data']['id'] == b['id']
            if b['review_state'] != 'APPROVED':
                api('/order/refunds/business/' + str(b['id']) + '/review', {'control_version': b['control_version'], 'platform_version': b['platform']['version'], 'purpose': 'DIFFERENCE' if index == 1 else 'CANCEL', 'goods_action': 'KEEP' if index == 1 else 'REFUSAL', 'reason': '[验收] 退差价继续履约' if index == 1 else '[验收] 平台退款成功但买家拒收待退回', 'evidence': 'TEST-REFUND-' + str(index)})
            b = api('/order/refunds/business/' + str(b['id']))['data']
            assert b['goods_state'] == ('DONE' if index == 1 else 'WAIT_ACTION')
            assert b['funds_state'] == ('NONE' if index == 1 else 'SUCCESS')
            assert b['state'] == 'PROCESSING' and b['order_id'] == c['order_id']
            evidence.append({k: b[k] for k in ['id', 'refund_sn', 'refund_id', 'goods_state', 'funds_state', 'state']})
        c['refunds'] = evidence
        case.save()
        # A second observation of the same records must not create another business refund.
        replay = api('/order/refunds/sync', {'channel_id': c['channel_id'], 'mode': 'IDS', 'refund_ids': [price_id, refusal_id], 'request_key': 'REFUND_REPLAY_' + c['case_id']})['data']
        wait_for(lambda: api('/order/refunds/tasks/' + str(replay['id']))['data'], lambda t: t['state'] == 'COMPLETED', 'Replay incomplete', 120)
        assert api('/order/refunds/business?' + urllib.parse.urlencode({'channelId': c['channel_id']}))['data']['total'] == 2
        assert stock(c) == before, 'Refund ingestion/review changed stock'
        assert next(r for r in api('/order/refunds/configs')['data']['configs'] if r['channel_id'] == c['channel_id'])['cursor_time'] == initial_cursor
        o = case.order()
        assert o['refund_block_reason'] and not o['release_eligible']
        listed = api('/order/orders/business/list?' + urllib.parse.urlencode({'tid': c['order_sn']}))['rows']
        assert len(listed) == 1 and listed[0]['refund_block_reason'] and not listed[0]['release_eligible']
        links = api('/order/refunds/orders/' + str(c['order_id']))['data']
        assert len(links['platform']) == len(links['business']) == 2
        logs = api('/channel/platform/log-center?' + urllib.parse.urlencode({'category': 'REFUND', 'numberType': 'PLATFORM_REFUND', 'number': refusal_id, 'allTime': 'true'}))['data']['rows']
        assert logs and all(r['entry_kind'] == 'DATA_SYNC' and r['environment'] == 'LOCAL' and r['http_status'] is None for r in logs)
        by_tk = api('/channel/platform/log-center?' + urllib.parse.urlencode({'numberType': 'AUTO', 'number': evidence[1]['refund_sn'], 'allTime': 'true'}))['data']['rows']
        assert by_tk, 'TK cannot resolve to its source synchronization log'
        with sync_playwright() as pw:
            browser = pw.chromium.launch(headless=True)
            context = browser.new_context(viewport={'width': 1660, 'height': 1100})
            context.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])
            page = context.new_page()
            errors = []
            page.on('pageerror', lambda e: errors.append(str(e)))
            page.goto(BASE + '/oms-order/platformRefunds?' + urllib.parse.urlencode({'number': refusal_id}), wait_until='networkidle')
            expect(page.get_by_role('heading', name='平台退款', exact=True, level=1)).to_be_visible()
            expect(page.get_by_text(refusal_id, exact=True).first).to_be_visible()
            page.screenshot(path=str(OUT / 'platform-refunds.png'), animations='disabled')
            page.goto(BASE + '/oms-order/businessRefunds?id=' + str(evidence[1]['id']), wait_until='networkidle')
            dialog = page.get_by_role('dialog', name='业务退款详情 · 仅退款', exact=True)
            expect(dialog.get_by_text('平台已退款', exact=True)).to_be_visible()
            expect(dialog.get_by_text('待货物处理', exact=True)).to_be_visible()
            expect(dialog.get_by_text('取消商品 · 买家拒收', exact=True)).to_be_visible()
            page.screenshot(path=str(OUT / 'refusal-detail.png'), animations='disabled')
            page.goto(BASE + '/oms-order/business?view=business&orderId=' + str(c['order_id']), wait_until='networkidle')
            drawer = page.locator('.el-drawer:visible')
            drawer.get_by_role('tab', name='退款记录', exact=True).click()
            expect(drawer.get_by_text(refusal_id, exact=True)).to_be_visible()
            page.screenshot(path=str(OUT / 'order-refund-records.png'), animations='disabled')
            page.goto(BASE + '/oms-channel/platformLogs?' + urllib.parse.urlencode({'category': 'REFUND', 'number': evidence[1]['refund_sn'], 'numberType': 'AUTO', 'allTime': 'true'}), wait_until='networkidle')
            expect(page.get_by_role('heading', name='平台交互日志', level=1)).to_be_visible()
            expect(page.locator('.platform-log-center > .el-table .el-table__row').get_by_text('读取退款推送库', exact=True).first).to_be_visible()
            page.screenshot(path=str(OUT / 'refund-sync-logs.png'), animations='disabled')
            page.goto(BASE + '/oms-order/refundSettings', wait_until='networkidle')
            expect(page.get_by_role('heading', name='退款接入设置', exact=True, level=1)).to_be_visible()
            page.screenshot(path=str(OUT / 'refund-settings.png'), animations='disabled')
            assert not errors, errors
            browser.close()
        report = {'case_id': c['case_id'], 'order_sn': c['order_sn'], 'order_id': c['order_id'], 'channel_id': c['channel_id'], 'platform_tid': c['platform_tid'], 'refunds': evidence, 'manual_task': task['id'], 'missing_id': missing_id, 'missing_result': 'NOT_FOUND', 'replay_task': replay['id'], 'log_ids': [r['id'] for r in logs], 'stock_unchanged': True, 'independent_cursor_unchanged': True, 'real_platform_refund_called': False, 'goods_still_pending': True, 'url': BASE + '/oms-order/businessRefunds?id=' + str(evidence[1]['id'])}
        save(report)
        print(json.dumps(report, ensure_ascii=True))
    finally:
        request('/auth/logout', token=token, method='DELETE')


if __name__ == '__main__':
    main()
