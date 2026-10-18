"""Continue the retained local TEST exchange; keep its new XS ready for inspection."""
import json
import subprocess
import sys
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE
from test_multi_virtual_shipping import sql, quote, wait_for
from verify_exchange_ownership import Case

OUT = Path(__file__).resolve().parents[2] / 'outputs/exchange-controls'
ORDER = 'qm_oms_saas_order'
INVENTORY = 'qm_oms_saas_inventory'


def main():
    token = login()
    c = json.loads((OUT / 'cut-case.json').read_text('utf-8'))
    case = Case.__new__(Case)
    case.token, case.c = token, c
    case.path = OUT / 'cut-case.json'
    identity = {'id': c['closed_exchange_id']}
    evidence_path = OUT / 'continuation.json'
    evidence = json.loads(evidence_path.read_text('utf-8')) if evidence_path.exists() else {}
    try:
        e = case.exchange(identity)
        original = case.order()
        returned = case.api('/order/exchanges/' + str(e['id']) + '/return')['data']['actual']
        assert e['source_kind'] == 'INTERNAL' and e['return_qc'] == 'PASSED'
        assert returned['posted_quantity'] == 2 and returned['document_count'] == 2
        assert original['shipped_quantity'] == 10
        old = [s for s in e['shipments'] if s['fulfillment_round'] == 1]
        assert len(old) == 2 and all(s['status'] == 'VOIDED' and s['shipped_quantity'] == 0 for s in old)
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            context = browser.new_context(viewport={'width': 1700, 'height': 1150})
            context.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])
            page = context.new_page()
            errors = []
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.goto(BASE + '/oms-order/exchanges?exchangeId=' + str(e['id']), wait_until='networkidle')
            drawer = page.locator('.el-drawer:visible')
            if not e.get('fulfillment_rounds'):
                expect(drawer.get_by_role('button', name='继续换出', exact=True)).to_be_visible()
                drawer.get_by_role('button', name='继续换出', exact=True).click()
                dialog = page.get_by_role('dialog', name='申请继续换出', exact=True)
                expect(dialog.get_by_text('本轮继续换出 2 件', exact=True)).to_be_visible()
                dialog.locator('.el-form-item').filter(has_text='协商依据').locator('textarea').fill('[验收] 买家确认继续换 B，复用已入账的 A 退回验收')
                dialog.locator('.el-form-item').filter(has_text='核对凭证').locator('textarea').fill('TEST-CONTINUE-B-NO-REFUND')
                for label in ['买家已同意继续换出该商品和数量', '已核对未退款、无退款处理中及其他冲突售后', '已核对本轮换出收货信息']:
                    dialog.locator('.el-checkbox').filter(has_text=label).click()
                dialog.get_by_role('button', name='提交继续换出申请', exact=True).click()
                e = wait_for(lambda: case.exchange(identity), lambda row: row['control_state'] == 'CONTINUE_REVIEW', 'Continuation request not retained')
                assert e['state'] == 'CLOSED' and e['fulfillment_round'] == 1
                assert len(e['shipments']) == 2 and e['return_result']['posted_quantity'] == 2
                assert sql("SELECT COUNT(*) FROM oms_order_stock_demand WHERE company_code='QM' AND platform_tid=" + quote('EXCHANGE:' + str(e['id']) + ':2'), INVENTORY) == '0'
                expect(drawer.get_by_role('button', name='审核继续换出', exact=True)).to_be_visible()
                page.screenshot(path=str(OUT / 'continuation-review.png'), animations='disabled')
                evidence['submission_no_stock_or_new_shipment'] = True
                evidence_path.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), 'utf-8')
            if e['control_state'] == 'CONTINUE_REVIEW':
                drawer.get_by_role('button', name='审核继续换出', exact=True).click()
                dialog = page.get_by_role('dialog', name='审核继续换出', exact=True)
                dialog.locator('textarea').fill('[验收] 已核对未退款、无其他冲突售后，退回 2 件已入账并验收')
                dialog.locator('.el-checkbox').filter(has_text='再次核对未退款且无冲突售后').click()
                dialog.get_by_role('button', name='保存审核结果', exact=True).click()
                e = wait_for(lambda: case.exchange(identity), lambda row: row['state'] == 'WAIT_STOCK' and row['fulfillment_round'] == 2, 'Continuation should wait for target stock')
            if e['state'] == 'WAIT_STOCK':
                assert e['round_quantity'] == 2 and not e['fulfillment_line_id']
                assert e['return_result']['posted_quantity'] == 2 and len(e['shipments']) == 2
                drawer.get_by_role('button', name='刷新进度', exact=True).click()
                expect(drawer.get_by_role('heading', name='等待换出库存', exact=True)).to_be_visible()
                page.screenshot(path=str(OUT / 'continuation-stock-wait.png'), animations='disabled')
                evidence['shortage_preserves_full_quantity'] = True
                evidence_path.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), 'utf-8')
            if not e.get('fulfillment_line_id'):
                payload = {'relationSn': 'TEST-CONTINUE-B-' + c['case_id'], 'wmsInventoryBatch': {
                    'companyCode': 'QM', 'storeCode': c['warehouses'][0]['code'], 'skuSn': c['target_sku'],
                    'batchCode': 'TEST-CONTINUE-B-' + c['case_id'], 'zpActualNumber': 2,
                    'zpAvailableNumber': 2, 'transactionPrice': 2.5}}
                result = subprocess.run(['wsl.exe', '--', 'docker', 'exec', '-i', 'oms-local-inventory', 'curl', '-fsS', '-H', 'from-source: inner', '-H', 'Content-Type: application/json', '--data-binary', '@-', 'http://localhost:9303/api/WmsInventory/addInventory?company_code=QM'], input=json.dumps(payload).encode(), capture_output=True, timeout=45)
                assert result.returncode == 0 and json.loads(result.stdout)['code'] == 200, 'TEST target replenishment failed'
                case.act(e, 'advance')
                e = wait_for(lambda: case.exchange(identity), lambda row: row.get('fulfillment_line_id') is not None, 'New round stock/materialization incomplete')
            if e['allocation_status'] != 'FULL':
                case.act(e, 'allocate')
                e = case.exchange(identity)
            current = [s for s in e['shipments'] if s['fulfillment_round'] == 2]
            assert len(current) == 1 and current[0]['quantity'] == 2
            assert current[0]['stock_reference'] == 'EXCHANGE:' + str(e['id']) + ':2'
            assert current[0]['wms_dispatch_state'] == 'NONE' and current[0]['shipped_quantity'] == 0
            assert len(e['shipments']) == 3 and e['return_sn'] == returned['return_sn']
            assert case.api('/order/exchanges/' + str(e['id']) + '/return')['data']['actual']['document_count'] == 2
            assert case.order()['shipped_quantity'] == 10
            page.reload(wait_until='networkidle')
            drawer.get_by_role('tab', name='换出发货', exact=True).click()
            expect(drawer.locator('.replacement-round')).to_have_count(2)
            expect(drawer.get_by_text('第 2 轮 · 继续换出', exact=True)).to_be_visible()
            expect(drawer.get_by_text('本轮已终止', exact=True)).to_be_visible()
            page.screenshot(path=str(OUT / 'continuation-rounds.png'), animations='disabled')
            assert not errors, errors
            browser.close()
        evidence.update(exchange_id=e['id'], exchange_sn=e['exchange_sn'], order_id=e['order_id'],
                        fulfillment_round=2, quantity=2, shipment_id=current[0]['id'],
                        shipment_sn=current[0]['shipment_sn'], stock_reference=current[0]['stock_reference'],
                        original_return_posted=2, return_document_count=2, original_sales_shipped=10,
                        status='READY_FOR_CARRIER_AND_DISPATCH', new_so_created=False, new_hh_created=False)
        evidence_path.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), 'utf-8')
        print('PASS same-HH continuation: submitted without stock, reviewed, waited for stock, new independent round allocated; retained', current[0]['shipment_sn'])
    finally:
        request('/auth/logout', token=token, method='DELETE')


if __name__ == '__main__':
    main()
