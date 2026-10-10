"""Exercise only the user-authorized named local store in explicit simulated mode."""
import json
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE, LOGS

def main():
    target=json.loads((LOGS/'tmall-simulation-target.json').read_text(encoding='utf-8'))
    channel=target['channelId'];token=login()
    try:
        initial=request(f'/channel/platform/shops/{channel}',token=token)['data']
        assert initial['channel_name']=='青木天猫旗舰店' and initial['simulation'],initial
        for action in ['simulate','query/shop','query/subscription','refresh']:
            request(f'/channel/platform/shops/{channel}/{action}',{},token,'POST')
        detail=request(f'/channel/platform/shops/{channel}',token=token)['data']
        assert detail['auth_label']=='已授权' and detail['service_label']=='服务有效'
        assert detail['availability']=='模拟对接成功（授权与服务有效）' and not detail['can_authorize']
        logs=request(f'/channel/platform/logs?channelId={channel}&pageSize=20',token=token)['data']['rows']
        assert all(row['result']=='SUCCESS' and json.loads(row['request_summary']).get('simulation') for row in logs[:4])
        (LOGS/'tmall-simulation-result.json').write_text(json.dumps({'shop':detail['channel_name'],'channelId':channel,'simulation':True,'authorization':detail['auth_label'],'subscription':detail['service_label'],'authExpiresAt':detail['token_expires_at'],'serviceExpiresAt':detail['service_expires_at'],'logIds':[r['id'] for r in logs[:4]]},ensure_ascii=False,indent=2),encoding='utf-8')
        with sync_playwright() as p:
            browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1600,'height':1100})
            context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=context.new_page();page.set_default_timeout(20000)
            errors=[];page.on('pageerror',lambda error:errors.append(str(error)))
            page.goto(BASE+'/oms-channel/channel',wait_until='networkidle')
            page.add_style_tag(content='*{animation:none!important;transition:none!important}')
            expect(page.get_by_text('模拟对接成功（授权与服务有效）',exact=True).first).to_be_visible()
            page.screenshot(path=str(LOGS/'tmall-simulation-list.png'),full_page=True,animations='disabled')
            page.get_by_role('button',name='平台对接',exact=True).first.click()
            drawer=page.locator('.channel-platform-drawer')
            expect(drawer.get_by_text('模拟对接',exact=True)).to_be_visible()
            expect(drawer.get_by_text('模拟对接成功（授权与服务有效）',exact=True)).to_be_visible()
            expect(drawer.get_by_role('button',name='模拟授权成功',exact=True)).to_be_enabled()
            expect(drawer.get_by_role('button',name='刷新令牌',exact=True)).to_be_enabled()
            page.screenshot(path=str(LOGS/'tmall-simulation-detail.png'),full_page=True,animations='disabled')
            drawer.get_by_role('button',name='查询平台店铺',exact=True).click();page.wait_for_load_state('networkidle')
            drawer.get_by_role('button',name='查看交互日志',exact=True).click()
            management=page.get_by_role('dialog',name='平台服务管理',exact=True)
            expect(management.get_by_text('模拟授权和服务订购成功',exact=True).first).to_be_visible()
            assert management.get_by_text('本地模拟',exact=True).count()>=4
            page.screenshot(path=str(LOGS/'tmall-simulation-logs.png'),full_page=True,animations='disabled')
            assert not errors,errors
            browser.close()
        print('PASS simulated Tmall connection:',json.dumps({'channelId':channel,'shop':detail['channel_name'],'authorization':detail['auth_label'],'subscription':detail['service_label'],'simulation':True,'logIds':[r['id'] for r in logs[:4]]},ensure_ascii=False))
    finally:
        request('/auth/logout',token=token,method='DELETE')

if __name__=='__main__':main()
