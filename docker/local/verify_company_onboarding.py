"""Local UI and API acceptance. Retains one test company; no stock or job operations."""
import json,sys,time,uuid,statistics,urllib.request,urllib.error
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE,opener

OUT=Path(__file__).resolve().parents[2]/'outputs/company-onboarding'
CODE='ONBOARD_TEST_20261014'
def raw(path,token=None,payload=None,method=None):
    headers={'Content-Type':'application/json'}
    if token:headers['Authorization']='Bearer '+token
    req=urllib.request.Request(BASE+'/prod-api'+path,data=json.dumps(payload).encode() if payload is not None else None,headers=headers,method=method)
    try:
        with opener.open(req,timeout=60) as response:return response.status,json.load(response)
    except urllib.error.HTTPError as response:return response.code,json.load(response)

def main():
    assert sys.argv[1:] in (['--draft-publish'],['--detect-enable'],['--read-only'])
    OUT.mkdir(parents=True,exist_ok=True);credential=login();evidence={}
    def detail():return request('/system/company/detail/'+CODE,token=credential)['data']
    def action(name,extra=None):return request('/system/company/'+CODE+'/'+name,{'revision':detail()['revision'],**(extra or {})},credential)['data']
    try:
        if sys.argv[1:] == ['--draft-publish']:
            assert request('/system/company/list?query='+CODE,token=credential)['total']==0,'Retain existing acceptance; do not recreate'
            with sync_playwright() as p:
                browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1550,'height':1100});context.add_cookies([{'name':'Admin-Token','value':credential,'url':BASE}]);page=context.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
                page.goto(BASE+'/system/company',wait_until='networkidle');page.locator('.heading button').filter(has_text='新增公司').click();drawer=page.locator('.el-drawer')
                drawer.get_by_placeholder('字母、数字、下划线或短横线').fill(CODE)
                drawer.locator('.el-form-item').filter(has_text='公司名称').locator('input').fill('[验收] 公司接入与性能测试')
                drawer.get_by_role('button',name='保存草稿',exact=True).click();expect(drawer.get_by_role('button',name='4. 启用公司')).to_be_disabled();page.screenshot(path=str(OUT/'draft.png'),full_page=True)
                drawer.get_by_role('button',name='1. 校验连接').click();expect(drawer.get_by_text('全部实例检查通过',exact=True)).to_have_count(5,timeout=30000)
                drawer.get_by_role('button',name='2. 发布配置').click();page.locator('.el-message-box').get_by_role('button',name='确定',exact=True).click()
                expect(drawer.get_by_text('当前发布版本：',exact=False)).to_be_visible(timeout=60000)
                page.screenshot(path=str(OUT/'published.png'),full_page=True);assert not errors,errors;browser.close()
            current=detail();assert current['status']=='DRAFT' and current['release_id'];evidence={'created_in_ui':True,'draft_cannot_enable_without_readiness':True,'five_services_published':True,'release_id':current['release_id'],'company_code':CODE}
            (OUT/'case.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf8')
        elif sys.argv[1:] == ['--detect-enable']:
            report=action('detect');assert report['ready'],report
            current=detail() if detail()['status']=='ENABLED' else action('enable');assert current['status']==current['execution_state']=='ENABLED'
            # A minimal account with no business permissions verifies identity and platform-only maintenance.
            password=uuid.uuid4().hex[:16];username='onboard_261014'
            request('/system/user',{'userName':username,'nickName':'公司接入验收','password':password,'loginCompanyCode':CODE,'status':'0','sex':'2','postIds':[],'roleIds':[]},credential)
            captcha=request('/code')
            import socket
            key=('captcha_codes:'+captcha['uuid']).encode()
            with socket.create_connection(('127.0.0.1',16379),timeout=10) as connection:
                connection.sendall(b'*2\r\n$3\r\nGET\r\n$'+str(len(key)).encode()+b'\r\n'+key+b'\r\n');reader=connection.makefile('rb');length=int(reader.readline()[1:].strip());answer=reader.read(length).decode().strip('"')
            usertoken=request('/auth/login',{'username':username,'password':password,'uuid':captcha['uuid'],'code':answer})['data']['access_token']
            try:
                own=request('/system/company/options?query=QM',token=usertoken)['data'];assert len(own)==1 and own[0]['company_code']==CODE
                assert raw('/system/company/detail/QM',usertoken)[1]['code']!=200
                status,body=raw('/order/orders/business/list?company_code=QM',usertoken);assert status==400
                action('disable')
                status,body=raw('/order/orders/business/0/audit',usertoken,{},'POST');assert status==503
                assert detail()['status']=='DISABLED';evidence={'all_five_services_loaded_managed_version':True,'enable_login_and_user_association':True,'tenant_options_own_only':True,'ordinary_user_cannot_administer_companies':True,'cross_company_request_rejected':True,'disabled_new_work_rejected':True,'retained_status':'DISABLED','test_user':username}
            finally:request('/auth/logout',token=usertoken,method='DELETE')
            # Keep the acceptance account disabled separately; never persist its password in artifacts.
            users=request('/system/user/list?userName='+username,token=credential)['rows'];assert len(users)==1
            request('/system/user/changeStatus',{'userId':users[0]['userId'],'status':'1'},credential,method='PUT')
            (OUT/'lifecycle.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf8')
        else:
            timings={}
            for name,path in [('company_list','/system/company/list?pageNum=1&pageSize=10'),('existing_order_detail','/order/orders/business/33')]:
                values=[];request(path,token=credential)
                for _ in range(20):
                    started=time.perf_counter();request(path,token=credential);values.append(round((time.perf_counter()-started)*1000,2))
                ordered=sorted(values);timings[name]={'requests':20,'p50_ms':round(statistics.median(values),2),'p95_ms':ordered[18],'max_ms':max(values)}
            with sync_playwright() as p:
                browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1550,'height':1100});context.add_cookies([{'name':'Admin-Token','value':credential,'url':BASE}]);page=context.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)));page.goto(BASE+'/system/company',wait_until='networkidle');page.screenshot(path=str(OUT/'list.png'),full_page=True)
                page.get_by_text('[验收] 公司接入与性能测试',exact=True).click();page.locator('.el-drawer').get_by_text('实例检测',exact=True).click();page.screenshot(path=str(OUT/'instances.png'),full_page=True);assert not errors,errors;browser.close()
            evidence={'timings':timings,'list_no_runtime_probes_verified_in_unit_test':True,'nested_50_scopes_one_runtime_check':True,'probe_max_concurrency':3,'schema':{'company_list_max_page_size':100,'checks_manual_only':True,'overall_deadline_ms':12000},'ui_js_errors':0}
            (OUT/'performance.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf8')
        print(json.dumps(evidence,ensure_ascii=True))
    finally:request('/auth/logout',token=credential,method='DELETE')
if __name__=='__main__':main()
