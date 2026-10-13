"""Read-only real acceptance with intercepted failure, unknown, retry and configuration states."""
import copy
import json
import re
from pathlib import Path
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE
from verify_platform_shipping_flow import SN, signature

OUTPUT = Path(__file__).resolve().parents[2] / 'outputs/platform-shipping'


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    token = login()
    errors, writes = [], []
    try:
        before = request('/order/orders/shipments/3', token=token)['data']
        live = request('/order/orders/shipments/3/platform-shipping', token=token)['data']
        assert live['tasks'][0]['state'] == 'SUCCESS'
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            context = browser.new_context(viewport={'width': 1600, 'height': 1100})
            context.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])
            page = context.new_page()
            page.on('pageerror', lambda e: errors.append(str(e)))
            fixture, intercepted = None, []

            def route_handler(route):
                nonlocal fixture
                path = urlparse(route.request.url).path
                if fixture and '/shipments/3/platform-shipping' in path:
                    if route.request.method == 'POST':
                        intercepted.append(path)
                        fixture['tasks'][0]['state'] = 'SUCCESS'
                        fixture['tasks'][0]['last_error'] = ''
                    route.fulfill(json={'code': 200, 'data': fixture})
                elif route.request.method == 'GET':
                    route.continue_()
                else:
                    writes.append(path)
                    route.abort()

            page.route('**/prod-api/**', route_handler)
            page.goto(BASE + '/oms-order/shipments?number=' + SN, wait_until='networkidle')
            status = page.locator('.shipment-workspace .el-table__body .platform-shipping-status').first
            expect(status).to_contain_text('模拟回传成功')
            expect(status).to_contain_text('已模拟回传 6 / 已关联 6 件')
            status.get_by_role('button', name='模拟回传成功', exact=True).click()
            dialog = page.locator('.platform-shipping-dialog:visible')
            expect(dialog).to_contain_text('不向天猫发送')
            expect(dialog).to_contain_text('模拟成功 6 件')
            expect(dialog).to_contain_text('TEST-SF-' + SN)
            dialog.get_by_text('查看本次包裹商品与回传快照', exact=True).click()
            expect(dialog).to_contain_text('9001717917894739110')
            expect(dialog).to_contain_text('9006371393940767110')
            expect(dialog).to_contain_text('9007664503219726110')
            expect(dialog).to_contain_text('ACCEPT_OUT_20261012164334')
            expect(dialog.get_by_text('test01', exact=True)).to_be_visible()
            page.wait_for_timeout(300)
            page.screenshot(path=str(OUTPUT / 'retained-package-notification.png'))
            dialog.get_by_text('平台回传交互与处理日志', exact=True).click()
            expect(dialog).to_contain_text('模拟发送')
            expect(dialog).to_contain_text('生成任务')
            dialog.get_by_role('button', name='关闭', exact=True).click()

            # Unknown results expose query, never retry. Mutation is intercepted.
            fixture = copy.deepcopy(live)
            fixture['tasks'][0].update(state='UNKNOWN', last_error='回复丢失，请核对原模拟回执', success_at=None)
            status.get_by_role('button', name='模拟回传成功', exact=True).click()
            expect(dialog.get_by_role('button', name='核对原回执', exact=True)).to_be_visible()
            expect(dialog.get_by_role('button', name='按原快照重试', exact=True)).to_have_count(0)
            page.screenshot(path=str(OUTPUT / 'unknown-query-only.png'))
            dialog.get_by_role('button', name='核对原回执', exact=True).click()
            expect(dialog).to_contain_text('模拟成功 6 件')
            dialog.get_by_role('button', name='关闭', exact=True).click()

            fixture = copy.deepcopy(live)
            fixture['tasks'][0].update(state='FAILED', last_error='模拟服务明确拒绝，请检查后重试', success_at=None)
            page.locator('.shipment-workspace .el-table__body .platform-shipping-status').first.get_by_role('button').click()
            expect(dialog.get_by_role('button', name='按原快照重试', exact=True)).to_be_visible()
            expect(dialog.get_by_role('button', name='核对原回执', exact=True)).to_have_count(0)
            dialog.get_by_role('button', name='按原快照重试', exact=True).click()
            expect(dialog).to_contain_text('模拟成功 6 件')
            dialog.get_by_role('button', name='关闭', exact=True).click()
            fixture = None

            # Configuration lives in shipment management, not pulling management.
            page.get_by_role('button', name='平台回传配置', exact=True).click()
            config = page.locator('.el-dialog:visible').filter(has_text='店铺平台发货回传配置')
            config.locator('.el-select').click()
            page.locator('.el-select-dropdown:visible').get_by_text('青木天猫旗舰店', exact=True).click()
            expect(config.get_by_label('模拟回传', exact=True)).to_be_checked()
            expect(config).to_contain_text('已有测试单据可手动生成任务')
            page.screenshot(path=str(OUTPUT / 'shop-simulation-config.png'))
            config.get_by_role('button', name='取消', exact=True).click()

            # Shipment drawer also offers an independent platform status.
            page.get_by_role('button', name=SN, exact=True).first.click()
            drawer = page.locator('.el-drawer:visible')
            expect(drawer.locator('.platform-summary')).to_contain_text('模拟回传成功')
            drawer.locator('.platform-summary').get_by_role('button', name='模拟回传成功', exact=True).click()
            expect(dialog).to_contain_text('模拟成功 6 件')
            page.screenshot(path=str(OUTPUT / 'shipment-detail-notification.png'))
            page.set_viewport_size({'width': 390, 'height': 844})
            page.wait_for_timeout(300)
            task_heading = dialog.locator('.shipping-task').first.bounding_box()
            assert task_heading['x'] >= 0 and task_heading['x'] + task_heading['width'] <= 391
            page.screenshot(path=str(OUTPUT / 'mobile-notification.png'))
            assert len(intercepted) == 2 and any(path.endswith('/query') for path in intercepted) and any(path.endswith('/retry') for path in intercepted)
            assert not errors and not writes, {'errors': errors, 'real_writes': writes}
            browser.close()
        after = request('/order/orders/shipments/3', token=token)['data']
        assert signature(before) == signature(after)
        result = {'passed': True, 'real_ui_writes': writes, 'intercepted_task_actions': len(intercepted), 'console_errors': errors, 'warehouse_data_unchanged': True}
        (OUTPUT / 'ui-verification.json').write_text(json.dumps(result, indent=2), encoding='utf8')
        print(json.dumps(result))
    finally:
        request('/auth/logout', token=token, method='DELETE')


if __name__ == '__main__':
    main()
