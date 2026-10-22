"""Fresh-browser verification of message trace metadata and the local SkyWalking UI."""
import json
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,BASE


def main():
    output=Path(__file__).resolve().parents[2]/'outputs/tracing'
    case=json.loads((output/'message-acceptance.json').read_text())
    token=login()
    with sync_playwright() as playwright:
        browser=playwright.chromium.launch(headless=True)
        context=browser.new_context(viewport={'width':1600,'height':1050},permissions=['clipboard-read','clipboard-write'])
        context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}])
        page=context.new_page();errors=[];page.on('pageerror',lambda error:errors.append(str(error)))
        page.goto(BASE+'/monitor/messages?service=channel&event='+case['source_trace_id'],wait_until='networkidle')
        expect(page.locator('.messages .el-table__body-wrapper').first.get_by_text(case['event_id'],exact=True)).to_be_visible()
        page.get_by_role('button',name='详情 / 链路',exact=True).first.click()
        drawer=page.locator('.el-drawer:visible')
        expect(drawer.get_by_text('来源请求链路',exact=True)).to_be_visible()
        expect(drawer.get_by_text(case['source_trace_id'],exact=True)).to_be_visible()
        expect(drawer.get_by_text(case['execution_trace_id'],exact=True).first).to_be_visible()
        drawer.get_by_role('button',name='复制追踪号',exact=True).first.click()
        expect(page.get_by_text('追踪号已复制，可在链路平台按 Trace ID 查询',exact=True)).to_be_visible()
        assert page.evaluate('navigator.clipboard.readText()')==case['source_trace_id']
        link=drawer.get_by_role('link',name='打开链路平台').first
        assert link.get_attribute('href')=='http://localhost:18090/'
        assert link.get_attribute('rel')=='noopener noreferrer'
        page.screenshot(path=str(output/'message-trace.png'),full_page=True)
        page.keyboard.press('Escape')
        expect(drawer).not_to_be_visible()
        menu=page.locator('.sidebar-container').get_by_role('link',name='SkyWalking',exact=True)
        expect(menu).to_be_visible()
        assert menu.get_attribute('href')=='http://localhost:18090'
        assert menu.get_attribute('target')=='_blank'
        page.screenshot(path=str(output/'system-monitoring-menu.png'),full_page=True)
        with context.expect_page() as popup:
            menu.click()
        platform=popup.value;platform.wait_for_load_state('networkidle')
        assert platform.title() and 'SkyWalking' in platform.title()
        platform.screenshot(path=str(output/'skywalking.png'),full_page=True)
        assert not errors,errors
        browser.close()
    print('PASS message trace search, source/execution IDs, clipboard and system monitoring SkyWalking link')


if __name__=='__main__':main()
