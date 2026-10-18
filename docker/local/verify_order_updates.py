"""Retained order-update acceptance through a dedicated local read-only push source.

Only newly created synthetic shops/SKUs/orders are touched. No WMS dispatch,
real platform shipping, scheduler switch changes, or historical backfill.
"""
import hashlib
import json
import secrets
import sys
from datetime import datetime
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE
from test_multi_virtual_shipping import sql, quote, wait_for, stock
import verify_allocation_rules_v2 as fixture

OUT = Path(__file__).resolve().parents[2] / 'outputs/order-updates'
DB = 'oms_order_update_acceptance'
ORDER_DB = 'qm_oms_saas_order'


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    manifest = OUT / 'cases.json'
    if manifest.exists():
        state = json.loads(manifest.read_text(encoding='utf-8'))
    else:
        stamp = datetime.now().strftime('%Y%m%d%H%M%S')
        state = {'stamp': stamp, 'cases': []}
        for i, kind in enumerate(['recipient', 'goods-refund'], 1):
            key = stamp + str(i)
            state['cases'].append({'kind': kind, 'case_id': key, 'created': datetime.now().strftime('%Y-%m-%d %H:%M:%S'), 'sku': 'UPD_' + key, 'channel_name': '[订单更新验收] ' + kind + ' ' + key, 'seller': 'UPDATE_' + key, 'platform_tid': '994' + key, 'platform_oid': '994' + key + '01', 'warehouses': [{'code': 'UPD_V1_' + key, 'quantity': 6, 'package_count': 0}, {'code': 'UPD_V2_' + key, 'quantity': 4, 'package_count': 0}]})
        save(manifest, state)
    credential = login()
    def api(path, data=None):
        return fixture.api(path, credential, data, post=data is not None)
    def get(c):
        return api('/order/orders/business/' + str(c['order_id']))['data']
    def report(c):
        return api('/order/orders/platform/' + str(c['platform_id']) + '/updates')['data']
    def allocate(c):
        o = get(c)
        result = api('/order/orders/business/allocate-batch', {'items': [{'id': o['id'], 'control_version': o['control_version']}]})['data']
        assert result[0]['success'], result[0].get('message')
        return get(c)
    try:
        jobs = {j['jobId']: (j['status'], j['cronExpression']) for j in api('/schedule/job/list?pageNum=1&pageSize=100')['rows']}
        if not state.get('source_key'):
            sql('CREATE DATABASE IF NOT EXISTS ' + DB + ' CHARACTER SET utf8mb4', ORDER_DB)
            sql('CREATE TABLE IF NOT EXISTS jdp_tb_trade (tid BIGINT PRIMARY KEY,status VARCHAR(64),type VARCHAR(64),seller_nick VARCHAR(200),created DATETIME,modified DATETIME,jdp_modified DATETIME,jdp_response MEDIUMTEXT,KEY seller_time(seller_nick,jdp_modified,tid))', DB)
            username = 'upd_read_' + state['stamp']
            password = secrets.token_urlsafe(32)
            sql('CREATE USER IF NOT EXISTS ' + quote(username) + "@'%' IDENTIFIED WITH mysql_native_password BY " + quote(password) + '; ALTER USER ' + quote(username) + "@'%' IDENTIFIED WITH mysql_native_password BY " + quote(password) + '; GRANT SELECT ON ' + DB + '.* TO ' + quote(username) + "@'%'", ORDER_DB)
            try:
                response = api('/order/orders/sources', {'source_name': '[本地验收] 订单更新只读来源 ' + state['stamp'], 'host': 'mysql', 'port': 3306, 'database_name': DB, 'username': username, 'password': password, 'enabled': 1})
                state['source_key'] = response.get('data') or response['msg']
            except Exception as e:
                raise RuntimeError('Dedicated local read-only source setup failed: ' + str(e)[:200]) from None
            del password
            save(manifest, state)
        for c in state['cases']:
            fixture.MANIFEST = OUT / ('case-' + c['kind'] + '.json')
            if fixture.MANIFEST.exists():
                c.update(json.loads(fixture.MANIFEST.read_text(encoding='utf-8')))
            save(fixture.MANIFEST, c)
            if not c.get('seeded'):
                fixture.prepare_case(c, credential)
                c['seeded'] = True
                save(fixture.MANIFEST, c)
            if not c.get('source_bound'):
                config = next(v for v in api('/order/orders/configs')['data'] if v['channel_id'] == c['channel_id'])
                api('/order/orders/configs', {**config, 'source_key': state['source_key'], 'reset_cursor': 1})
                c['source_bound'] = True
                save(fixture.MANIFEST, c)
            if c['kind'] == 'recipient' and not c.get('allocated'):
                allocate(c)
                c['allocated'] = True
                c['original_shipments'] = [s['shipment_sn'] for s in get(c)['shipments']]
                save(fixture.MANIFEST, c)
            if not c.get('pulled'):
                raw = sql("SELECT raw_payload FROM oms_platform_order WHERE company_code='QM' AND id=" + str(c['platform_id']), ORDER_DB)
                payload = json.loads(raw)
                trade = payload['trade_fullinfo_get_response']['trade']
                if c['kind'] == 'recipient':
                    trade.update(receiver_state='上海市', receiver_city='上海市', receiver_district='验收新区', receiver_address='订单更新验收地址，无实际配送')
                else:
                    trade['orders']['order'][0].update(num=8, refund_status='WAIT_SELLER_AGREE', refund_id='TEST_REFUND_' + c['case_id'])
                modified = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
                trade['modified'] = modified
                body = json.dumps(payload, ensure_ascii=False)
                sql('INSERT INTO jdp_tb_trade(tid,status,type,seller_nick,created,modified,jdp_modified,jdp_response) VALUES (' + ','.join(quote(v) for v in [c['platform_tid'], 'WAIT_SELLER_SEND_GOODS', 'fixed', c['seller'], c['created'], modified, modified, body]) + ') ON DUPLICATE KEY UPDATE jdp_response=VALUES(jdp_response),modified=VALUES(modified),jdp_modified=VALUES(jdp_modified)', DB)
                c['stock_before_update'] = stock(c)
                save(fixture.MANIFEST, c)
                task = api('/order/orders/backfill', {'mode': 'TID', 'channel_ids': [c['channel_id']], 'tids': [c['platform_tid']], 'request_key': 'UPDATE_ACCEPT_' + c['case_id']})['data']['task_ids'][0]
                wait_for(lambda: api('/order/orders/tasks/' + str(task))['data'], lambda t: t['status'] in ['SUCCESS', 'PARTIAL'], 'Dedicated order update pull pending')
                r = wait_for(lambda: report(c), lambda r: r['change_pending'] == 1, 'Platform update was not blocked')
                assert stock(c) == c['stock_before_update'], 'Detecting an update changed stock'
                assert get(c)['review_status'] == 'PENDING'
                c['pull_task_id'] = task
                c['pulled'] = True
                save(fixture.MANIFEST, c)
            save(manifest, state)
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            context = browser.new_context(viewport={'width': 1500, 'height': 1100})
            context.add_cookies([{'name': 'Admin-Token', 'value': credential, 'url': BASE}])
            page = context.new_page()
            errors = []
            page.on('pageerror', lambda e: errors.append(str(e)))
            for c in state['cases']:
                fixture.MANIFEST = OUT / ('case-' + c['kind'] + '.json')
                page.goto(BASE + '/oms-order/business?view=business&orderId=' + str(c['order_id']), wait_until='networkidle')
                drawer = page.locator('.el-drawer:visible').first
                drawer.get_by_role('tab', name='订单更新', exact=True).click()
                panel = drawer.locator('.order-updates')
                expect(panel.get_by_text(c['platform_tid'], exact=False).first).to_be_visible()
                if c['kind'] == 'recipient' and not c.get('applied'):
                    expect(panel.get_by_role('button', name='应用收货信息', exact=True)).to_be_disabled()
                    panel.locator('textarea').fill('[验收测试] 客户已确认上海地址')
                    panel.get_by_role('button', name='先撤回原未发履约', exact=True).click()
                    page.get_by_role('button', name='确定', exact=True).click()
                    wait_for(lambda: get(c), lambda o: o['cutoff_state'] == 'REPLAN_READY', 'Local withdrawal not confirmed')
                    panel.get_by_role('button', name='刷新更新记录', exact=True).click()
                    expect(panel.get_by_role('button', name='应用收货信息', exact=True)).to_be_enabled()
                    page.screenshot(path=str(OUT / 'recipient-withdrawn.png'), full_page=True, animations='disabled')
                    panel.get_by_role('button', name='应用收货信息', exact=True).click()
                    page.get_by_role('button', name='确定', exact=True).click()
                    wait_for(lambda: report(c), lambda r: r['change_pending'] == 0, 'Address adoption pending')
                    o = get(c)
                    assert '上海市' in o['receiver_address'] and o['review_status'] == 'PENDING'
                    assert all(s['status'] == 'VOIDED' for s in o['shipments'])
                    snapshot = stock(c)
                    assert snapshot['company'][0]['reserved'] == 10 and sum(w['locked'] for w in snapshot['warehouse']) == 0
                    c['applied'] = True
                    save(fixture.MANIFEST, c)
                    page.screenshot(path=str(OUT / 'recipient-applied.png'), full_page=True, animations='disabled')
                if c['kind'] == 'recipient' and not c.get('reallocated'):
                    o = get(c)
                    result = api('/order/orders/business/approve-batch', {'orders': [{'id': o['id'], 'control_version': o['control_version']}], 'reason': '[验收测试] 订单更新后重新审核'})['data']
                    assert result[0]['success'], result
                    o = allocate(c)
                    new = [s for s in o['shipments'] if s['shipment_sn'] not in c['original_shipments']]
                    assert len(new) == 2 and all(s['wms_dispatch_state'] == 'NONE' for s in new)
                    assert o['allocation_rule']['receiver_province'] == '上海市'
                    for s in o['shipments']:
                        d = api('/order/orders/shipments/' + str(s['id']))['data']
                        assert ('上海市' in d['receiver_address']) == (s in new), 'Old shipment snapshot was overwritten'
                    c['reallocated'] = True
                    save(fixture.MANIFEST, c)
                if c['kind'] == 'goods-refund':
                    r = report(c)
                    assert {'GOODS', 'REFUND'} <= set(r['update_categories'].split(','))
                    assert stock(c) == c['stock_before_update']
                    assert get(c)['lines'][0]['quantity'] == 10
                    expect(panel.get_by_text('商品变更待处理', exact=True).first).to_be_visible()
                    expect(panel.get_by_role('button', name='应用收货信息', exact=True)).to_have_count(0)
                    page.screenshot(path=str(OUT / 'goods-refund-pending.png'), full_page=True, animations='disabled')
                    with page.expect_response(lambda response: '/updates/events/' in response.url and response.status == 200) as history_response:
                        panel.locator('.el-collapse-item__header').first.click()
                    assert isinstance(history_response.value.json()['data']['differences'], list)
                    assert not history_response.value.json()['data'].get('payload')
                    page.screenshot(path=str(OUT / 'history-expanded.png'), full_page=True, animations='disabled')
                    drawer.get_by_role('tab', name='商品明细', exact=True).click()
                    drawer.get_by_role('button', name='查看问题与处理记录', exact=True).click()
                    issues = page.get_by_role('dialog', name='订单问题与处理记录', exact=True)
                    expect(issues.get_by_text('订单更新', exact=True)).to_be_visible()
                    expect(issues.get_by_text('暂无当前问题，可查看历史处理记录', exact=True)).to_have_count(0)
                    issues.get_by_role('button', name='查看差异与处理更新', exact=True).click()
                    expect(drawer.locator('.order-updates').get_by_text('商品变更待处理', exact=True).first).to_be_visible()
                    wait_for(lambda: drawer.locator('.order-updates').bounding_box()['y'], lambda y: 70 <= y <= 200, 'Update entry did not scroll to the loaded differences', seconds=10)
                    page.screenshot(path=str(OUT / 'update-entry.png'), animations='disabled')
                c['business_url'] = BASE + '/oms-order/business?view=business&orderId=' + str(c['order_id'])
                save(fixture.MANIFEST, c)
                save(manifest, state)
            assert not errors, errors
            browser.close()
        assert jobs == {j['jobId']: (j['status'], j['cronExpression']) for j in api('/schedule/job/list?pageNum=1&pageSize=100')['rows']}
        save(OUT / 'verification.json', {'actual_tid_pull_verified': True, 'read_only_local_source': True, 'address_withdraw_and_apply_ui_verified': True, 'new_province_reallocation_verified': True, 'old_shipment_snapshots_preserved': True, 'goods_refund_stock_unchanged': True, 'no_scheduler_changes': True, 'no_warehouse_dispatch': True, 'browser_errors': errors, 'cases': [{'order_sn': get(c)['order_sn'], 'kind': c['kind'], 'url': c['business_url']} for c in state['cases']]})
        print('PASS retained order update TID pull, UI withdrawal/adoption and reallocation acceptance')
    finally:
        request('/auth/logout', token=credential, method='DELETE')


if __name__ == '__main__':
    main()
