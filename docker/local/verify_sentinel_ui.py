"""Verify the system monitoring link and an actual Sentinel dashboard login."""
import os
from pathlib import Path
import re
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,BASE
from local_db import ENV


def main():
    output=Path(__file__).resolve().parents[2]/'outputs/sentinel'
    output.mkdir(parents=True,exist_ok=True)
    token=login()
    username=os.environ.get('SENTINEL_DASHBOARD_USERNAME',ENV.get('SENTINEL_DASHBOARD_USERNAME','sentinel'))
    password=os.environ.get('SENTINEL_DASHBOARD_PASSWORD',ENV.get('SENTINEL_DASHBOARD_PASSWORD','sentinel'))
    with sync_playwright() as playwright:
        browser=playwright.chromium.launch(headless=True)
        context=browser.new_context(viewport={'width':1600,'height':1000})
        context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}])
        page=context.new_page();errors=[]
        page.on('pageerror',lambda error:errors.append(str(error)))
        page.goto(BASE+'/monitor/online',wait_until='networkidle')
        link=page.locator('.sidebar-container').get_by_role('link',name='Sentinel控制台',exact=True)
        expect(link).to_be_visible()
        assert link.get_attribute('href')=='http://localhost:8718'
        assert link.get_attribute('target')=='_blank'
        page.screenshot(path=str(output/'monitor-menu.png'),full_page=True)
        with context.expect_page() as popup:link.click()
        console=popup.value;console.on('pageerror',lambda error:errors.append(str(error)))
        console.wait_for_load_state('networkidle')
        expect(console).to_have_url(re.compile(r'localhost:8718/.*login'))
        console.locator('input[ng-model="username"]').fill(username)
        console.locator('input[ng-model="password"]').fill(password)
        console.locator('button[ng-click="login()"]').click()
        expect(console).not_to_have_url(re.compile(r'.*#/login.*'))
        expect(console.get_by_text('注销',exact=True)).to_be_visible()
        expect(console.locator('body')).to_contain_text('实时监控')
        expect(console.locator('body')).not_to_contain_text('\ufffd')
        console.screenshot(path=str(output/'dashboard.png'),full_page=True)
        assert not errors,errors
        browser.close()
    print('PASS system monitoring Sentinel link, new tab, login and dashboard rendering')


if __name__=='__main__':main()
