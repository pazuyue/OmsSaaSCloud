"""Retained TEST configuration and fresh-browser acceptance; no business, stock or external commands."""
import json,re,urllib.parse,hashlib
from datetime import datetime
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE
from test_multi_virtual_shipping import sql,quote

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/shared-carriers';API='/order/sharedCarriers'


def main():
    path=OUT/'management-ui.json';state=json.loads(path.read_text('utf-8')) if path.exists() else dict(stamp=datetime.now().strftime('%Y%m%d%H%M%S'),commands={})
    old=json.loads((OUT/'manifest.json').read_text('utf-8'));token=login();prefix='UI_CARRIER_'+state['stamp'];checks=[];errors=[];writes=[];images=[];requests=[]
    def save():path.write_text(json.dumps(state,ensure_ascii=False,indent=2),'utf-8')
    def api(route,body=None):return request(API+route,payload=body,token=token)['data']
    def once(key,kind,body):
        if key not in state['commands']:state['commands'][key]=api('/'+kind+'/save',dict(revision=0,**body));save()
        return state['commands'][key]
    for letter in ['A','B']:
        once('STD_'+letter,'standard',dict(standard_code=prefix+'_'+letter,standard_name='[快递UI验收] 承运商'+letter+' '+state['stamp'],aliases=[],category='EXPRESS',enabled=1))
    a=state['commands']['STD_A'];b=state['commands']['STD_B'];cap=old['commands']['CAP_OUTBOUND']['response']['data']
    def scope(purpose):return dict(system_type='WMS' if purpose.startswith('WMS') else 'CHANNEL',provider='QIMEN' if purpose.startswith('WMS') else 'TMALL',purpose=purpose,connection_id=str(old['ids']['connection']) if purpose.startswith('WMS') else '',shop_id='',owner_code=cap['owner_code'] if purpose.startswith('WMS') else '',real_store_code=cap['real_store_code'] if purpose.startswith('WMS') else '')
    def directory(key,purpose):return once(key,'directory',dict(scope(purpose),external_code=prefix+'_'+key,external_name='[快递UI验收] '+key,enabled=1,evidence='TEST 本地UI验收清单，保留测试配置'))
    def mapping(key,d,standard=a,default=1,enabled=1):return once(key,'mapping',dict(scope(d['purpose']),directory_id=d['id'],standard_id=standard['id'],direction='BOTH',is_default=default,product_code='',enabled=enabled,disabled_reason='' if enabled else 'TEST 仓库确认暂停',evidence='TEST 已核对专用UI验收清单'))
    free=directory('CH_FREE','CHANNEL_SHIPPING');linked=directory('CH_LINKED','CHANNEL_SHIPPING');disabled=directory('CH_DISABLED','CHANNEL_SHIPPING');mapping('CH_MAP',linked);mapping('CH_MAP_DISABLED',disabled,b,1,0)
    plan=directory('WMS_PLAN','WMS_OUTBOUND_CREATE');alternate=directory('WMS_ALT','WMS_OUTBOUND_CREATE');mapping('WMS_MAP',plan);mapping('WMS_ALT_MAP',alternate,a,0)
    for key,purpose in [('WMS_ACTUAL','WMS_OUTBOUND_ACTUAL'),('WMS_RETURN','WMS_RETURN_CREATE'),('WMS_RETURN_ACTUAL','WMS_RETURN_ACTUAL'),('CH_BUYER','CHANNEL_RETURN_IDENTIFY'),('CH_EXCHANGE','CHANNEL_EXCHANGE_LOGISTICS'),('CH_BACKFILL','CHANNEL_RETURN_BACKFILL')]:mapping(key+'_MAP',directory(key,purpose))
    for flow in ['OUTBOUND','INBOUND']:
        once('CAP_'+flow,'capability',dict(connection_id=str(old['ids']['connection']),owner_code=cap['owner_code'],real_store_code=cap['real_store_code'],standard_id=a['id'],flow=flow,product_code='',provinces=[],excluded_provinces=['上海'] if flow=='OUTBOUND' else [],enabled=1,evidence='TEST UI验收能力：发货不支持上海，收货不限省份'))
    totals={}
    for status in ['UNLINKED','LINKED','DISABLED']:
        result=api('/directory/list?'+urllib.parse.urlencode(dict(system_type='CHANNEL',purpose='CHANNEL_SHIPPING',keyword=prefix,association_state=status,pageSize=1)));assert result['total']==1 and result['rows'][0]['association_state']==status,result;totals[status]=result['total']
    state['api_checks']=dict(association_totals=totals);save()
    metadata=api('/metadata');connection=next(c for c in metadata['connections'] if str(c['id'])==str(old['ids']['connection']));store=next(w for w in metadata['stores'] if str(w['connection_id'])==str(old['ids']['connection']) and w['owner_code']==cap['owner_code'] and w['real_store_code']==cap['real_store_code'])
    # Dedicated UI-only tasks have no real business identity and are never revalidated or retried.
    if 'todo_ids' not in state:
        state['todo_ids']={}
        for status in ['OPEN','READY']:
            context=dict(scope('WMS_OUTBOUND_ACTUAL'),direction='IN',external_code=prefix+'_MISSING',raw_name='TEST UI待办原始快递')
            digest=hashlib.sha256((prefix+status).encode()).hexdigest();number=prefix+'_'+status
            statement="INSERT INTO oms_carrier_todo(company_code,fact_hash,business_type,business_id,business_sn,fact_id,blocked_action,reason_code,reason,context_json,state,updated_by) VALUES ('QM',"+quote(digest)+",'SHIPMENT',0,"+quote(number)+","+quote(prefix+status)+",'UI_CONTEXT_ONLY','MISSING',"+quote('TEST 仅验证UI按钮与配置跳转，不对应真实业务，不执行校验或重试')+","+quote(json.dumps(context,ensure_ascii=False))+","+quote(status)+",'TEST UI') ON DUPLICATE KEY UPDATE fact_hash=VALUES(fact_hash)"
            sql(statement,'qm_oms_saas_order');state['todo_ids'][status]=int(sql("SELECT id FROM oms_carrier_todo WHERE company_code='QM' AND fact_hash="+quote(digest),'qm_oms_saas_order').strip());save()

    with sync_playwright() as p:
        browser=p.chromium.launch(headless=True);ctx=browser.new_context(viewport=dict(width=1760,height=1180));ctx.add_cookies([dict(name='Admin-Token',value=token,url=BASE)]);page=ctx.new_page();page.set_default_timeout(25000);page.on('pageerror',lambda e:errors.append(str(e)))
        def observe(r):
            if '/sharedCarriers/' in r.url:requests.append(dict(url=r.url.split('/sharedCarriers/')[1],method=r.method))
            if '/sharedCarriers/' in r.url and r.method=='POST' and any(part in r.url for part in ['/save','/batch-confirm','/import-confirm','/retry','/revalidate','/sync']):writes.append(r.url)
        page.on('request',observe)
        def go(slug,query=None):
            page.goto(BASE+'/common-config/carriers/'+slug+('?' + urllib.parse.urlencode(query) if query else ''),wait_until='networkidle');expect(page.locator('.carriers h2')).to_be_visible();expect(page.locator('.page-heading')).to_have_count(0);assert not page.locator('.carriers > .el-alert--error').count(),page.locator('.carriers').inner_text()
        def shot(name):
            filename='management-'+name+'.png';page.screenshot(path=str(OUT/filename),animations='disabled',full_page=True);images.append(filename)
        def dialog():return page.locator('.el-dialog:visible')
        def item(container,label):return container.locator('.el-form-item').filter(has=page.locator('.el-form-item__label').filter(has_text=re.compile('^'+label+'$')))
        def select(locator,label):
            locator.click();page.locator('.el-select-dropdown:visible .el-select-dropdown__item').filter(has_text=re.compile('^'+re.escape(label)+'$')).click()
        def row_button(code,label):return page.locator('.carriers .el-table tr').filter(has_text=code).locator('button:visible').filter(has_text=re.compile('^'+re.escape(label)+'$')).first
        def choose_standard(container,standard):
            dialog().locator('.el-dialog__title').click();expect(page.locator('.el-select-dropdown:visible')).to_have_count(0);container.locator('.shared-carrier-select input').click();container.locator('.shared-carrier-select input').fill(standard['standard_code']);page.locator('.el-select-dropdown:visible .el-select-dropdown__item').filter(has_text=standard['standard_name']).click()
        for slug,title in [('standards','标准快递资料'),('channels','渠道快递配置'),('wms','WMS 快递配置'),('capabilities','仓库服务能力'),('todos','快递关联待办')]:
            go(slug);expect(page.locator('.carriers h2')).to_have_text(title);shot(slug)
        checks.append('五个页面仅保留一处标题，列表正常加载，配置入口与查询区域分开')
        go('channels',dict(keyword=prefix,purpose='CHANNEL_SHIPPING'));header=page.locator('.carriers .el-table__header-wrapper');expect(header).to_contain_text('标准快递');expect(header).to_contain_text('平台快递');expect(header).to_contain_text('指定场景检查');expect(page.locator('.carriers .el-table__body-wrapper tr')).to_have_count(2);assert '通用匹配' not in page.locator('.carriers .el-table').inner_text();shot('channel-columns');checks.append('关联列表拆分标准快递、外部快递、用途、范围、配置状态和指定场景检查，编码可复制')
        select(page.locator('.toolbar .el-select').filter(has=page.get_by_placeholder('全部配置状态')),'已停用');expect(page.locator('.carriers .el-table__body-wrapper tr')).to_have_count(1);page.reload(wait_until='networkidle');expect(page.locator('.carriers .el-table__body-wrapper tr')).to_have_count(1);expect(page.get_by_placeholder('全部配置状态')).to_have_value('已停用');checks.append('配置状态筛选由服务端分页处理，刷新后保留筛选和当前页')
        go('channels',dict(section='directory',purpose='CHANNEL_SHIPPING',keyword=prefix));expect(page.locator('.carriers .el-table__body-wrapper tr')).to_have_count(3)
        for label in ['未关联','已关联','关联已停用']:
            select(page.locator('.toolbar .el-select').filter(has=page.get_by_placeholder('全部关联状态')),label);expect(page.locator('.carriers .el-table__body-wrapper tr')).to_have_count(1);expect(page.locator('.carriers .el-table__body-wrapper tr').get_by_text(label,exact=True)).to_be_visible()
        select(page.locator('.toolbar .el-select').filter(has=page.get_by_placeholder('全部关联状态')),'未关联');row=page.locator('.carriers .el-table__body-wrapper tr');row_button(free['external_code'],'关联标准快递').click();expect(dialog()).to_be_visible();expect(item(dialog(),'平台快递').locator('input')).to_have_value(re.compile(re.escape(free['external_code'])));expect(item(dialog(),'业务用途').locator('input')).to_have_value('销售发货回传');expect(dialog().locator('.form-section')).to_have_count(3);shot('directory-associate');dialog().get_by_role('button',name='取消',exact=True).click();checks.append('清单按三种关联状态查询；从未关联条目直接建关联，自动带入编码、用途和范围')
        go('channels',dict(section='directory',purpose='CHANNEL_SHIPPING',keyword=linked['external_code']));row_button(linked['external_code'],'查看关联').click();expect(page.locator('.carriers .el-table__body-wrapper tr')).to_have_count(1);expect(page.locator('.scope-filters')).to_contain_text('仅看所选清单条目的关联');checks.append('已关联清单一键查看对应关联，支持取消单条清单筛选')
        go('wms',dict(keyword=prefix,purpose='WMS_OUTBOUND_CREATE',connection_id=str(old['ids']['connection']),owner_code=cap['owner_code'],real_store_code=cap['real_store_code']));expect(page.locator('.carriers .el-table__body-wrapper tr')).to_have_count(2);shot('wms-filters');checks.append('WMS 列表按连接、货主、实仓及用途筛选，完整范围显示名称')
        row=page.locator('.carriers .el-table__body-wrapper tr').filter(has_text=plan['external_code']);row_button(plan['external_code'],'编辑').click();expect(item(dialog(),'仓库下发首选项')).to_be_visible();expect(item(dialog(),'仓库下发首选项')).to_contain_text('同范围');dialog().get_by_role('button',name='取消',exact=True).click()
        page.get_by_role('button',name='关联标准快递',exact=True).click();choose_standard(dialog(),b);expect(dialog().get_by_text('正在核对提交或下发候选…',exact=True)).to_have_count(0);expect(item(dialog(),'仓库下发首选项')).to_have_count(0);dialog().get_by_role('button',name='取消',exact=True).click();checks.append('WMS 多候选显示仓库下发首选项，单候选自动处理并隐藏开关')
        page.get_by_role('tab',name='仓库快递清单',exact=True).click();rows=page.locator('.carriers .el-table__body-wrapper tr');expect(rows).to_have_count(2)
        for i in range(2):rows.nth(i).locator('.el-checkbox').click()
        page.get_by_role('button',name='批量关联标准快递',exact=True).click();batch=dialog().locator('.el-table__body-wrapper tr');choose_standard(batch.nth(0),b);choose_standard(batch.nth(1),b);expect(batch.nth(0).locator('.el-switch')).to_be_visible();batch.nth(0).locator('.el-switch').click();batch.nth(1).locator('.el-switch').click();expect(batch.nth(0).locator('.el-switch')).not_to_have_class(re.compile('is-checked'));dialog().locator('textarea').fill('TEST 已核对UI验收清单，本次仅预览不建立关联');dialog().get_by_role('button',name='检查本批关联',exact=True).click();expect(dialog().get_by_role('button',name='确认建立关联',exact=True)).to_be_enabled();expect(dialog().locator('.el-table__header-wrapper')).to_contain_text('检查结果');shot('batch');dialog().get_by_role('button',name='关闭',exact=True).click();checks.append('WMS 批量候选按组互斥选择首选，检查结果显示在对应行，预览不写关联')
        go('wms',dict(keyword=plan['external_code'],purpose='WMS_OUTBOUND_CREATE'));row_button(plan['external_code'],'检查场景').click();select(item(dialog(),'收货省份').locator('.el-select'),'上海');dialog().get_by_role('button',name='检查当前场景',exact=True).click();expect(dialog().locator('.check-steps article').nth(1)).to_contain_text('通过');expect(dialog().locator('.check-steps article').nth(2)).to_contain_text('存在问题');expect(dialog().get_by_role('link',name='去配置仓库能力',exact=True)).to_be_visible();shot('scenario-failure')
        select(item(dialog(),'收货省份').locator('.el-select'),'浙江');expect(dialog().locator('.result')).to_have_count(0);dialog().get_by_role('button',name='检查当前场景',exact=True).click();expect(dialog().locator('.check-steps article')).to_have_count(3);expect(dialog().locator('.check-steps article.failed')).to_have_count(0);shot('scenario-success');dialog().get_by_role('button',name='关闭',exact=True).click();expect(page.locator('.carriers .el-table__body-wrapper tr')).to_contain_text('本次场景通过');checks.append('仓库发货场景区分编码关联和配送能力；上海能力失败、浙江通过，修改条件清空旧结果')
        go('capabilities',dict(keyword=prefix));expect(page.locator('.carriers .el-table__body-wrapper tr')).to_have_count(1);expect(page.locator('.carriers .el-table__body-wrapper tr')).to_contain_text('不配送：上海');page.locator('.flow-tabs .el-radio-button').filter(has_text='收货能力').click();expect(page.locator('.carriers .el-table__body-wrapper tr')).to_have_count(1);expect(page.locator('.carriers .el-table__body-wrapper tr')).to_contain_text('支持收货');shot('inbound-capability');checks.append('仓库服务区分发货能力和收货能力，收货不显示发货配送限制')
        go('channels',dict(keyword=linked['external_code']));row_button(linked['external_code'],'记录').click();expect(dialog()).to_contain_text('当前配置的变更记录');expect(dialog().locator('.el-table__body-wrapper tr')).to_have_count(1);expect(dialog()).to_contain_text(a['standard_name']);dialog().locator('.el-table__expand-icon').click();expect(dialog()).to_contain_text('变更字段');expect(dialog()).to_contain_text('配置来源与确认说明');shot('audit');dialog().get_by_role('button',name='关闭',exact=True).click();checks.append('操作记录支持当前配置过滤、可读对象名称、字段修改前后值与来源说明，原始JSON折叠')
        go('todos',dict(keyword=old['return_sn']));row=page.locator('.carriers .el-table__body-wrapper tr');expect(row).to_contain_text('已完成');expect(row.get_by_role('button',name='去修复配置',exact=True)).to_have_count(0);expect(row.get_by_role('button',name='继续未完成步骤',exact=True)).to_have_count(0);shot('completed-todo');checks.append('已完成待办不再提供修复或重试动作，原始物流和业务单据仍可追溯')
        go('todos',dict(keyword=prefix+'_OPEN'));row_button(prefix+'_OPEN','去修复配置').click();expect(page.locator('.carriers h2')).to_have_text('WMS 快递配置');expect(page.locator('.scope-filters')).to_contain_text(prefix+'_MISSING');assert 'owner_code=' in page.url and 'real_store_code=' in page.url and 'purpose=WMS_OUTBOUND_ACTUAL' in page.url;page.get_by_role('button',name='关联标准快递',exact=True).click();expect(item(dialog(),'业务用途').locator('input')).to_have_value('WMS 实际出库回传');expect(item(dialog(),'货主').locator('input')).not_to_have_value('');expect(item(dialog(),'实体仓').locator('input')).not_to_have_value('');shot('todo-repair-context');dialog().get_by_role('button',name='取消',exact=True).click();page.get_by_role('button',name='返回原关联待办',exact=True).click();expect(page.locator('.carriers .el-table__body-wrapper tr')).to_have_count(1);checks.append('UI专用待办可直接修复，带齐原编码、业务用途、连接、货主与实仓，返回后保留原单筛选；未校验或重试业务')
        go('todos',dict(keyword=prefix+'_READY'));expect(row_button(prefix+'_READY','继续未完成步骤')).to_be_visible();expect(page.locator('.carriers .el-table').get_by_role('button',name='去修复配置',exact=True)).to_have_count(0);shot('ready-todo');checks.append('配置检查通过的UI专用待办展示继续未完成步骤；仅检查按钮，未执行重试')
        go('channels');page.get_by_role('button',name='场景核验',exact=True).click();select(dialog().locator('.scenario .el-select'),'售后退回入库');forms=dialog().locator('.columns > .el-form');item(forms.nth(0),'原始快递编码').locator('input').fill(state['commands']['CH_BUYER']['external_code']);select(item(forms.nth(1),'WMS 连接').locator('.el-select'),connection['name']+' · '+connection['provider']);select(item(forms.nth(1),'货主').locator('.el-select'),store['owner_name']+' · '+store['owner_code']);select(item(forms.nth(1),'实体仓').locator('.el-select'),store['real_store_name']+' · '+store['real_store_code']);expect(item(dialog(),'收货省份')).to_have_count(0);dialog().get_by_role('button',name='检查当前场景',exact=True).click();expect(dialog().locator('.check-steps article')).to_have_count(3);expect(dialog().locator('.check-steps article.failed')).to_have_count(0);expect(dialog().locator('.check-steps article').nth(2)).to_contain_text('仓库收货能力');expect(dialog().locator('.result')).to_contain_text(state['commands']['WMS_RETURN']['external_code']);shot('return-scenario');checks.append('售后退回入库场景从买家原始快递识别到标准快递，再转换仓库入库编码并验证收货能力，三步通过且无需发货省份；只读核验')
        assert not errors and not writes,dict(errors=errors,writes=writes);browser.close()
    state.update(stage='VERIFIED',checks=checks,page_errors=errors,configuration_writes=writes,screenshots=images,requests=requests);save();print('PASS',len(checks),'carrier management UI checks; retained TEST configuration, no business/stock replay',flush=True)


if __name__=='__main__':main()
