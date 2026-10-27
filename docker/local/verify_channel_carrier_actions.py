"""Retained TEST configuration and read-only browser acceptance of channel action directions."""
import json,re,time,urllib.parse
from datetime import datetime
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/shared-carriers';API='/order/sharedCarriers'
ACTIONS=[('CHANNEL_SHIPPING','销售发货回传','OUT'),('CHANNEL_EXCHANGE_LOGISTICS','换货发出物流回传','OUT'),('CHANNEL_RETURN_IDENTIFY','识别买家寄回物流','IN'),('CHANNEL_RETURN_BACKFILL','向平台回填寄回物流','OUT')]


def main():
    path=OUT/'channel-actions.json';state=json.loads(path.read_text('utf-8')) if path.exists() else dict(stamp=datetime.now().strftime('%Y%m%d%H%M%S'),commands={},checks=[])
    token=login();stamp=state['stamp']
    def save():path.write_text(json.dumps(state,ensure_ascii=False,indent=2),'utf-8')
    def api(p,b=None):return request(p,payload=b,token=token)['data']
    def once(key,p,b):
        if key not in state['commands']:state['commands'][key]=api(p,b);save()
        return state['commands'][key]
    def scope(purpose):return dict(system_type='CHANNEL',provider='TMALL',purpose=purpose,connection_id='',shop_id='',owner_code='',real_store_code='')
    def check(key,scenario,evidence):
        state['checks']=[c for c in state['checks'] if c['key']!=key]+[dict(key=key,scenario=scenario,result='PASS',evidence=evidence)];save();print('PASS',key,scenario,flush=True)
    for letter in ['C','D']:
        once('STD_'+letter,API+'/standard/save',dict(revision=0,standard_code='TEST_CPA_'+letter+'_'+stamp,standard_name='[渠道用途TEST] 承运商'+letter+' '+stamp,aliases=['渠道用途验收'+letter+'_'+stamp],category='EXPRESS',enabled=1))
    carrier=state['commands']['STD_C'];other=state['commands']['STD_D']
    def directory(key,purpose,code):return once(key,API+'/directory/save',dict(scope(purpose),revision=0,external_code=code,external_name='[渠道用途TEST] '+code,enabled=1,evidence='TEST 本地配置验收，不代表官方正式编码'))
    for purpose,label,direction in ACTIONS:
        code=('TEST-CP-BUYER/' if direction=='IN' else 'TEST-CP-'+purpose+'_')+stamp
        if purpose=='CHANNEL_RETURN_BACKFILL' and 'BACKFILL_MISSING' not in state:
            source=scope('CHANNEL_RETURN_IDENTIFY');source['external_code']=state['commands']['DIR_CHANNEL_RETURN_IDENTIFY']['external_code'];probe=api(API+'/preview',dict(source=source,target=scope(purpose)));assert probe['source']['available'] is True and probe['target']['state']=='MISSING';state['BACKFILL_MISSING']=probe;save()
        d=directory('DIR_'+purpose,purpose,code)
        mapped=once('MAP_'+purpose,API+'/mapping/save',dict(scope(purpose),revision=0,directory_id=d['id'],standard_id=carrier['id'],direction='BOTH',is_default=1,product_code='',enabled=1,evidence='TEST 明确业务用途，服务端自动确定方向'))
        assert mapped['direction']==direction and mapped['is_default']==(0 if direction=='IN' else 1),mapped
    source=dict(scope('CHANNEL_RETURN_IDENTIFY'),external_code=state['commands']['DIR_CHANNEL_RETURN_IDENTIFY']['external_code'])
    converted=api(API+'/preview',dict(source=source,target=scope('CHANNEL_RETURN_BACKFILL')));assert converted['target']['external_code']==state['commands']['DIR_CHANNEL_RETURN_BACKFILL']['external_code'];assert converted['source']['external_code']!=converted['target']['external_code']
    check('SOURCE_AND_BACKFILL','识别买家寄回物流和平台回填使用独立关联；识别配置不能替代回填配置',dict(before=state['BACKFILL_MISSING'],after=converted))
    wrong=api(API+'/preview',dict(source=dict(scope('CHANNEL_SHIPPING'),external_code=state['commands']['DIR_CHANNEL_SHIPPING']['external_code']),target=scope('CHANNEL_RETURN_BACKFILL')));assert wrong['source']['state']=='INVALID_CONTEXT'
    check('SERVER_DIRECTIONS','四种渠道用途均由服务端确定方向；提交双向不会产生双向关联，运行时错误方向被拒绝',dict(mappings=[state['commands']['MAP_'+p] for p,_,_ in ACTIONS],wrong_direction=wrong))
    stats=api(API+'/mapping/output-options',dict(scope('CHANNEL_SHIPPING'),items=[dict(id=state['commands']['MAP_CHANNEL_SHIPPING']['id'],standard_id=carrier['id']),dict(id=0,standard_id=carrier['id']),dict(id=0,standard_id=other['id'])]));assert [r['other_count'] for r in stats['rows']]==[0,1,0],stats
    check('PREFERRED_LOOKUP','一次查询同时检查编辑当前项、新增同承运商项与单一候选；排除当前关联并返回其他首选标识',stats)
    extras=[]
    for suffix in ['A','B']:extras.append(directory('EXTRA_'+suffix,'CHANNEL_SHIPPING','TEST-CP-SHIP-EXTRA-'+stamp+'-'+suffix))
    errors=[];browser_checks=[];queries=[];writes=[]
    with sync_playwright() as p:
        browser=p.chromium.launch(headless=True);ctx=browser.new_context(viewport=dict(width=1760,height=1260));ctx.add_cookies([dict(name='Admin-Token',value=token,url=BASE)]);page=ctx.new_page();page.set_default_timeout(20000);page.on('pageerror',lambda e:errors.append(str(e)))
        def observe(r):
            if '/sharedCarriers/mapping/output-options' in r.url:queries.append(len((r.post_data_json or {}).get('items',[])))
            if '/sharedCarriers/' in r.url and r.method=='POST' and any(k in r.url for k in ['/save','/batch-confirm','/import-confirm']):writes.append(r.url)
        page.on('request',observe)
        def item(dialog,label):return dialog.locator('.el-form-item').filter(has=page.locator('.el-form-item__label').filter(has_text=re.compile('^'+label+'$')))
        def select_purpose(dialog,label):
            item(dialog,'业务用途').locator('.el-select').click();page.locator('.el-select-dropdown:visible .el-select-dropdown__item').filter(has_text=re.compile('^'+label+'$')).click()
        def choose_standard(container,standard):
            page.locator('.el-dialog:visible .el-dialog__title').click();expect(page.locator('.el-select-dropdown:visible')).to_have_count(0);container.locator('.shared-carrier-select input').click();container.locator('.shared-carrier-select input').fill(standard['standard_code']);page.locator('.el-select-dropdown:visible .el-select-dropdown__item').filter(has_text=standard['standard_name']).click()
        page.goto(BASE+'/common-config/carriers/channels',wait_until='networkidle');page.get_by_role('button',name='关联标准快递',exact=True).click();dialog=page.locator('.el-dialog:visible')
        for purpose,label,direction in ACTIONS:
            select_purpose(dialog,label);expect(item(dialog,'转换方向')).to_have_count(0);expect(item(dialog,'业务用途').locator('input')).to_have_value(label);expect(item(dialog,'平台提交首选项')).to_have_count(0);page.screenshot(animations='disabled',path=str(OUT/('channel-action-'+purpose.lower()+'.png')));browser_checks.append(label+'无需选择方向；未选候选时不显示首选项')
        select_purpose(dialog,'销售发货回传');choose_standard(dialog,carrier);expect(item(dialog,'平台提交首选项')).to_be_visible();expect(item(dialog,'平台提交首选项')).to_contain_text(state['commands']['DIR_CHANNEL_SHIPPING']['external_code']);page.screenshot(animations='disabled',path=str(OUT/'channel-action-multiple.png'));browser_checks.append('多个提交候选才显示平台提交首选项，并显示已配置的首选标识')
        with page.expect_response(lambda r:'/mapping/output-options' in r.url and any(i.get('standard_id')==other['id'] for i in r.request.post_data_json.get('items',[]))):choose_standard(dialog,other)
        expect(dialog.get_by_text('正在核对平台提交候选…',exact=True)).to_have_count(0);expect(item(dialog,'平台提交首选项')).to_have_count(0);browser_checks.append('单一提交候选自动处理并隐藏首选项')
        select_purpose(dialog,'识别买家寄回物流');expect(item(dialog,'平台提交首选项')).to_have_count(0);dialog.get_by_role('button',name='取消',exact=True).click()
        page.get_by_role('tab',name='平台快递清单',exact=True).click();page.locator('.carriers .toolbar .el-input input').first.fill('TEST-CP-SHIP-EXTRA-'+stamp);page.get_by_role('button',name='查询',exact=True).click();rows=page.locator('.carriers .el-table__body-wrapper tr');expect(rows).to_have_count(2)
        for i in range(2):rows.nth(i).locator('.el-checkbox').click()
        page.get_by_role('button',name='批量关联标准快递',exact=True).click();dialog=page.locator('.el-dialog:visible');batch=dialog.locator('.el-table__body-wrapper tr');expect(batch).to_have_count(2)
        choose_standard(batch.nth(0),other)
        with page.expect_response(lambda r:'/mapping/output-options' in r.url and len(r.request.post_data_json.get('items',[]))==2):choose_standard(batch.nth(1),other)
        expect(dialog.locator('.el-table__header-wrapper')).to_contain_text('平台提交首选项');assert '方向' not in dialog.locator('.el-table__header-wrapper').inner_text();expect(batch.nth(0).locator('.el-switch')).to_be_visible();batch.nth(0).locator('.el-switch').click();dialog.locator('textarea').fill('TEST 已核对平台正式清单；本次仅预览批量关联');page.get_by_role('button',name='检查本批关联',exact=True).click();expect(dialog.get_by_role('button',name='确认建立关联',exact=True)).to_be_enabled();page.screenshot(animations='disabled',path=str(OUT/'channel-action-batch.png'));dialog.get_by_role('button',name='关闭',exact=True).click();browser_checks.append('批量关联按业务用途固定方向；多个同承运商候选明确首选后预览通过')
        page.get_by_role('button',name='场景核验',exact=True).click();dialog=page.locator('.el-dialog:visible');dialog.locator('.scenario .el-select').click();page.locator('.el-select-dropdown:visible .el-select-dropdown__item').filter(has_text='售后退回入库').click();source_form=dialog.locator('.columns > .el-form').first;expect(item(source_form,'业务用途')).to_contain_text('识别买家寄回物流');expect(item(source_form,'业务用途').locator('.el-select')).to_have_count(0);browser_checks.append('场景核验按所选业务自动确定来源和目标用途')
        assert 2 in queries and not errors and not writes,dict(queries=queries,errors=errors,writes=writes);browser.close()
    state['browser']=dict(checks=browser_checks,page_errors=errors,output_lookup_batch_sizes=queries,configuration_writes=writes);state['stage']='VERIFIED';save();print('PASS',len(browser_checks),'channel action/preference browser checks; no configuration or stock replay',flush=True)


if __name__=='__main__':main()
