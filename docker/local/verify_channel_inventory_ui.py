"""Headless browser acceptance; uses a fresh context and never changes business stock."""
import json
import subprocess
import sys
import urllib.request
from pathlib import Path
from playwright.sync_api import sync_playwright

BASE = 'http://localhost:8088'
LOGS = Path(__file__).resolve().parent / 'logs'
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def request(path, payload=None, token=None, method=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(BASE + '/prod-api' + path, data=json.dumps(payload).encode() if payload is not None else None, headers=headers, method=method)
    with opener.open(req, timeout=30) as response:
        body = json.load(response)
    assert body['code'] == 200, (path, body.get('msg'))
    return body

def main():
    LOGS.mkdir(exist_ok=True)
    captcha = request('/code')
    command = ['docker', 'exec', 'oms-local-redis', 'redis-cli', '--raw', 'GET', 'captcha_codes:' + captcha['uuid']]
    if sys.platform == 'win32':
        command = ['wsl', '-d', 'Ubuntu-24.04', '--'] + command
    answer = subprocess.check_output(command, text=True).strip().strip('"')
    token = request('/auth/login', {'username': 'admin', 'password': 'admin123', 'uuid': captcha['uuid'], 'code': answer})['data']['access_token']
    print('UI login complete', flush=True)
    try:
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            context = browser.new_context(viewport={'width': 1440, 'height': 1000}, accept_downloads=True)
            context.add_cookies([{'name': 'Admin-Token', 'value': token, 'url': BASE}])
            page = context.new_page()
            page.set_default_timeout(15000)
            errors = []
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.goto(BASE + '/oms-inventory/channelInventory', wait_until='networkidle')
            print('UI page loaded', flush=True)
            page.get_by_role('button', name='查看详情', exact=True).first.wait_for()
            page.screenshot(path=str(LOGS / 'channel-inventory-desktop.png'), full_page=True)
            print('UI desktop screenshot complete', flush=True)
            with page.expect_download() as download:
                page.get_by_role('button', name='导出当前页').click()
            print('UI CSV download received', flush=True)
            download.value.save_as(str(LOGS / 'channel-inventory-current-page.csv'))
            assert '普通可售配额' in (LOGS / 'channel-inventory-current-page.csv').read_text(encoding='utf-8-sig')
            page.get_by_role('button', name='查看详情', exact=True).first.click()
            drawer = page.locator('.channel-inventory-drawer')
            drawer.get_by_text('可追溯剩余锁库', exact=False).wait_for()
            print('UI detail loaded', flush=True)
            for tab in ['分货执行记录', '订单批次明细', '订单操作记录', '锁库来源 / 释放']:
                drawer.get_by_role('tab', name=tab, exact=True).click()
                page.wait_for_load_state('networkidle')
                assert '记录加载失败' not in drawer.inner_text()
                print('UI tab passed:', tab, flush=True)
            page.screenshot(path=str(LOGS / 'channel-inventory-detail.png'), full_page=True)
            drawer.get_by_role('button', name='查看分货单', exact=True).first.click()
            page.get_by_role('button', name='返回渠道库存').wait_for()
            page.get_by_role('button', name='返回渠道库存').click()
            page.locator('.channel-inventory-drawer').get_by_text('可追溯剩余锁库', exact=False).wait_for()
            page.locator('.channel-inventory-drawer').get_by_role('button', name='查看批次', exact=True).first.click()
            page.locator('.inventory-drawer').get_by_text('锁库去向', exact=True).wait_for()
            page.locator('.inventory-drawer').get_by_role('button', name='返回渠道库存').click()
            page.locator('.channel-inventory-drawer').get_by_text('可追溯剩余锁库', exact=False).wait_for()
            print('UI batch link and return passed', flush=True)
            page.goto(BASE + '/oms-channel/channel', wait_until='networkidle')
            page.locator('.app-container .el-table').get_by_role('button', name='库存', exact=True).first.click()
            page.wait_for_url(lambda url: '/oms-inventory/channelInventory' in url and 'channelId=' in url)
            page.wait_for_load_state('networkidle')
            assert 'channelId=' in page.url
            page.goto(BASE + '/oms-inventory/productInventory', wait_until='networkidle')
            page.get_by_role('button', name='渠道库存', exact=True).first.click()
            page.wait_for_url(lambda url: '/oms-inventory/channelInventory' in url and 'skuSn=' in url)
            page.wait_for_load_state('networkidle')
            assert 'skuSn=' in page.url
            page.get_by_role('button', name='重置', exact=True).click()
            page.wait_for_load_state('networkidle')
            page.get_by_role('button', name='查看详情', exact=True).first.wait_for()
            page.set_viewport_size({'width': 390, 'height': 844})
            page.wait_for_timeout(400)
            page.screenshot(path=str(LOGS / 'channel-inventory-mobile.png'), full_page=True)
            page.get_by_role('button', name='查看详情', exact=True).first.click()
            page.locator('.channel-inventory-drawer').get_by_text('可追溯剩余锁库', exact=False).wait_for()
            page.wait_for_timeout(400)
            assert page.locator('.channel-inventory-drawer').bounding_box()['x'] < 1, 'Mobile drawer must use the full screen'
            page.screenshot(path=str(LOGS / 'channel-inventory-mobile-detail.png'))
            assert not errors, errors
            print('PASS desktop/mobile channel inventory, four detail tabs, CSV export, product/channel links, batch/allocation return; no browser errors')
            context.close()
            browser.close()
    finally:
        request('/auth/logout', {}, token, method='DELETE')

if __name__ == '__main__':
    main()
