"""Verify unconfigured Tmall UI, draft app/binding, logs and callback handling without a platform app."""
import json
import subprocess
import sys
import time
import urllib.parse
from playwright.sync_api import sync_playwright, expect
from verify_goods_workspace_ui import login, request, BASE, LOGS

def cleanup(channel, app):
    # Remove only the exact disposable fixture IDs created by this script.
    sql=f"DELETE FROM channel_oauth_state WHERE channel_id={channel}; DELETE FROM channel_interaction_log WHERE channel_id={channel} OR app_id={app}; DELETE FROM channel_platform_binding WHERE channel_id={channel}; DELETE FROM t_channel WHERE channel_id={channel} AND channel_name LIKE 'UIPLAT%'; DELETE FROM channel_platform_app WHERE id={app} AND name LIKE 'UIPLAT%';"
    code="import sys;sys.path.insert(0,'/mnt/d/Users/yueguang/OmsSaaSCloud/docker/local');from local_db import execute;execute("+repr(sql)+",'qm_oms_saas_channel');print('Temporary platform UI fixtures removed')"
    command=['wsl','-d','Ubuntu-24.04','--','python3','-'] if sys.platform=='win32' else ['python3','-']
    subprocess.run(command,input=code.encode(),check=True)

def main():
    token=login();prefix='UIPLAT'+str(int(time.time()));channel=app=0
    try:
        request('/channel/channel',{'channelName':prefix,'channelType':'TM','enabled':1,'toChannelEnabled':1,'syncEnabled':0,'mmodelType':1},token,'POST')
        channel=request('/channel/channel/list?channelName='+prefix,token=token)['rows'][0]['channelId']
        initial=request(f'/channel/platform/shops/{channel}',token=token)['data']
        assert initial['auth_label']=='待配置' and initial['service_label']=='未核验' and not initial['can_authorize']
        with sync_playwright() as p:
            browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1600,'height':1050})
            context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=context.new_page();page.set_default_timeout(20000)
            errors=[];page.on('pageerror',lambda error:errors.append(str(error)))
            page.goto(BASE+'/oms-channel/channel',wait_until='networkidle')
            page.add_style_tag(content='*{animation:none!important;transition:none!important}')
            page.get_by_role('button',name='平台应用',exact=True).click()
            management=page.get_by_role('dialog',name='平台服务管理',exact=True)
            management.get_by_role('button',name='新增应用',exact=True).click()
            form=page.get_by_role('dialog',name='天猫平台应用配置',exact=True)
            form.locator('.el-form-item').filter(has_text='应用名称（必填）').locator('input').fill(prefix)
            form.get_by_role('button',name='保存配置',exact=True).click();expect(form).not_to_be_visible()
            app=next(a['id'] for a in request('/channel/platform/apps',token=token)['data'] if a['name']==prefix)
            expect(management.get_by_text(prefix,exact=True)).to_be_visible()
            page.screenshot(path=str(LOGS/'channel-platform-apps.png'),full_page=True,animations='disabled')
            management.get_by_role('button',name='关闭',exact=True).click()
            connect=page.get_by_role('button',name='平台对接',exact=True).first
            connect.click()
            drawer=page.locator('.channel-platform-drawer');expect(drawer.get_by_text('未绑定平台应用',exact=True)).to_be_visible()
            expect(drawer.get_by_role('button',name='前往授权',exact=True)).to_be_disabled()
            drawer.locator('.binding-form .el-select').click();page.locator('.el-select-dropdown:visible').get_by_text(prefix+'（未启用）',exact=True).click()
            drawer.get_by_placeholder('填写淘宝店铺 sid，用于授权身份校验').fill('987654'+str(channel))
            drawer.get_by_role('button',name='保存对接配置',exact=True).click()
            expect(drawer.get_by_text('应用未配置完整或已停用',exact=True)).to_be_visible()
            expect(drawer.get_by_role('button',name='前往授权',exact=True)).to_be_disabled()
            expect(drawer.get_by_role('button',name='刷新令牌',exact=True)).to_be_disabled()
            page.screenshot(path=str(LOGS/'channel-platform-detail.png'),full_page=True,animations='disabled')
            drawer.get_by_role('button',name='保存提醒设置',exact=True).click();page.wait_for_load_state('networkidle')
            drawer.get_by_role('button',name='查看交互日志',exact=True).click()
            expect(management.get_by_text('绑定店铺',exact=True)).to_be_visible()
            management.get_by_role('button',name='查看',exact=True).first.click()
            summary=page.get_by_role('dialog',name='交互摘要',exact=True);expect(summary).to_be_visible();summary.get_by_role('button',name='关闭',exact=True).click()
            page.screenshot(path=str(LOGS/'channel-platform-logs.png'),full_page=True,animations='disabled')
            management.get_by_role('tab',name='到期提醒',exact=True).click();page.wait_for_load_state('networkidle')
            assert prefix not in management.locator('.el-table:visible').inner_text()
            management.get_by_role('button',name='关闭',exact=True).click();drawer.get_by_role('button',name='关闭详情',exact=True).click()
            page.set_viewport_size({'width':1100,'height':800});connect.click();expect(drawer.get_by_role('button',name='关闭详情',exact=True)).to_be_in_viewport()
            page.screenshot(path=str(LOGS/'channel-platform-compact.png'),full_page=True,animations='disabled')
            page.goto(BASE+'/channel-authorize?error=access_denied&state=discard',wait_until='networkidle')
            expect(page.get_by_text('授权未完成，请返回店铺管理重新发起。',exact=True)).to_be_visible()
            assert '?' not in page.url
            assert 'discard' not in (page.evaluate('JSON.stringify(sessionStorage)') or '')
            assert not errors,errors
            browser.close()
        print('PASS browser: draft app, blocked authorization, binding, reminders, logs, fixed footer and sanitized callback')
    finally:
        cleanup(channel,app)
        request('/auth/logout',token=token,method='DELETE')

if __name__=='__main__':main()
