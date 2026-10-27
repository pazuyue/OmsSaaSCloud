"""Browser acceptance of the full inventory-check form, background lifecycle and result navigation."""
import json,re,time,urllib.parse
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE
from verify_full_stock_check import baseline

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/full-stock-check';API='/supplychain/warehouseControl';ROUTE='/oms-supplychain/warehouseReconciliation'


def main():
    state=json.loads((OUT/'manifest.json').read_text('utf-8'));old=json.loads((ROOT/'outputs/warehouse-control/manifest.json').read_text('utf-8'));token=login();before=baseline();checks=[];errors=[];shots=[]
    api=lambda path,body=None:request(path,payload=body,token=token)['data']
    with sync_playwright() as p:
        browser=p.chromium.launch(headless=True);ctx=browser.new_context(viewport={'width':1660,'height':1160},accept_downloads=True);ctx.add_cookies([dict(name='Admin-Token',value=token,domain='127.0.0.1',path='/')]);page=ctx.new_page();page.set_default_timeout(30000);page.on('pageerror',lambda e:errors.append(str(e)))
        def shot(name,locator=None):
            (locator or page).screenshot(path=str(OUT/name));shots.append(name)
        try:
            page.goto(BASE+ROUTE);page.get_by_role('button',name='新建库存检查',exact=True).click();form=page.locator('.inventory-check-create:visible');expect(form.get_by_role('button',name=re.compile('^全仓检查'))).to_have_class(re.compile('active'));assert form.locator('textarea').count()==0;checks.append('默认全仓检查，隐藏强制手填SKU的步骤')
            select=form.locator('.el-select input').first;select.click();page.locator('.el-select-dropdown:visible').get_by_text(re.compile(r'\[库存调拨TEST\] source '+old['stamp']+'$')).click();expect(form.get_by_text(re.compile('合并 2 个虚仓'))).to_be_visible();assert old['store_source2'] in form.inner_text();checks.append('选仓后显示真实货主、实仓和两个聚合虚仓')
            form.get_by_role('button',name='指定商品检查',exact=False).click();form.locator('textarea').fill('0001\n0001\nA');expect(form.get_by_text('当前填写 2 个不同SKU。',exact=False)).to_be_visible();shot('01-selected-scope.png',form);checks.append('指定商品支持粘贴、去重计数并保留前导零')
            # Read-only scoped SKU picker, then return to the full-census form.
            picker=form.locator('.el-select').nth(1);picker.locator('input.el-select__input').fill('WC_'+old['stamp']+'_12');option=page.locator('.el-select-dropdown:visible').get_by_text(re.compile('WC_'+old['stamp']+'_12'));expect(option).to_be_visible();option.click();assert 'WC_'+old['stamp']+'_12' in form.locator('textarea').input_value();checks.append('复用当前货主实仓的库存SKU搜索，不要求商品处于销售状态')
            form.get_by_role('button',name=re.compile('^全仓检查')).click();form.get_by_text('OMS＋仓库完整清单（导入）',exact=True).click();form.locator('input[type=file]').set_input_files(str(OUT/'仓库完整商品清单.csv'));expect(form.get_by_text(re.compile('已导入 '+str(len(state['warehouse_skus']))+' 个SKU'))).to_be_visible();form.get_by_placeholder('记录导出时间、货主实仓和清单来源，便于复核').fill('本地TEST页面验收完整清单 '+state['stamp']);form.get_by_text('已核对清单属于上方货主实仓，包含全部商品及零库存商品',exact=True).click();shot('02-full-scope.png',form);checks.append('导入仓库完整清单后显示SKU数、来源和覆盖确认；明确不是自动仓库全量枚举')
            with page.expect_response(lambda r:urllib.parse.urlparse(r.url).path=='/prod-api'+API+'/reconciliations' and r.request.method=='POST') as response:form.get_by_role('button',name='提交全仓检查',exact=True).click()
            result=response.value.json();assert result['code']==200,result;id=result['data'];state['ids']['browser']=id;(OUT/'manifest.json').write_text(json.dumps(state,ensure_ascii=False,indent=2),'utf-8')
            drawer=page.locator('.control-detail:visible');panel=drawer.locator('.full-check-results');expect(panel).to_be_visible()
            stop=panel.get_by_role('button',name='结束当前商品后停止',exact=True)
            if stop.is_visible():
                stop.click()
                for _ in range(30):
                    d=api(API+'/reconciliations/'+str(id))
                    if d['state']=='STOPPED':break
                    time.sleep(.1)
                assert d['state']=='STOPPED',d['state'];drawer.get_by_role('button',name='刷新进度',exact=True).click();expect(panel.get_by_role('button',name='继续未查商品',exact=True)).to_be_visible();shot('03-stopped-task.png',drawer);panel.get_by_role('button',name='继续未查商品',exact=True).click();checks.append('页面停止等待当前商品完成，继续操作沿用原任务和已成功快照')
            else:checks.append('该小范围任务已在按钮出现前完成；停止/继续由后台回归验证')
            drawer.get_by_role('button',name='关闭',exact=True).click();page.goto(BASE+'/index')
            for _ in range(90):
                d=api(API+'/reconciliations/'+str(id))
                if d['state']=='COMPLETED':break
                time.sleep(.2)
            assert d['state']=='COMPLETED',d
            page.goto(BASE+ROUTE+'?id='+str(id));drawer=page.locator('.control-detail:visible');panel=drawer.locator('.full-check-results');expect(panel.get_by_text('商品清单已收集完成',exact=True)).to_be_visible();expect(panel.get_by_text('本次清单已查完；',exact=False)).to_be_visible();shot('04-full-task.png',drawer);checks.append('关闭页面后后台继续；返回显示真实商品总数、成功/失败、检查窗口及覆盖来源')
            sku='WC_FULL_ONLY_'+state['stamp'];panel.locator('.el-form input').first.fill(sku);panel.get_by_role('button',name='查询',exact=True).click();row=panel.locator('.el-table__body-wrapper tr.el-table__row');expect(row).to_have_count(1);expect(row.get_by_text('差额 +7',exact=True)).to_be_visible();expect(row).to_contain_text('WMS冻结 3');shot('05-exact-difference.png',drawer);checks.append('仓库独有商品明确显示OMS0、WMS7、差额+7；WMS冻结3独立展示')
            with page.expect_download() as download:panel.get_by_role('button',name='导出当前筛选',exact=True).click()
            dest=OUT/'筛选结果.csv';download.value.save_as(str(dest));csv=dest.read_text('utf-8-sig');assert sku in csv and len(csv.strip().splitlines())==2;checks.append('导出使用当前筛选，仅导出所选SKU，编码保留字符串格式')
            row.get_by_role('button',name='查看该商品快照、虚仓来源与仓库交互',exact=True).click();expect(drawer.get_by_text('这是全仓任务中的商品快照；调整前请重新检查最新库存。',exact=True)).to_be_visible();expect(drawer.get_by_role('button',name='申请差异调整',exact=True)).to_have_count(0);expect(drawer.get_by_text('库存查询交互',exact=True)).to_be_visible();shot('06-sku-snapshot.png',drawer);drawer.get_by_role('button',name='返回来源全仓检查',exact=True).click();expect(drawer.locator('.full-check-results')).to_be_visible();checks.append('商品快照可追溯虚仓、仓库交互并返回全仓任务，禁止使用旧子快照直接调整')
            # Validate the explicitly retained failure snapshot through its child.
            failed=state['checks'][4]['result']['failed']['rows'][0];page.goto(BASE+ROUTE+'?id='+str(failed['child_id']));expect(page.locator('.control-detail:visible').get_by_text('库存查询交互',exact=True)).to_be_visible();checks.append('失败子任务保留原仓库查询交互，修复后只重试该商品')
            assert not errors,errors
        finally:browser.close()
    after=baseline();assert before==after,'Browser acceptance changed stock or protected documents'
    (OUT/'browser-check.json').write_text(json.dumps(dict(result='PASS',checks=checks,js_errors=errors,screenshots=shots,stock_unchanged=True,task_id=id),ensure_ascii=False,indent=2),'utf-8');print('PASS',len(checks),'browser checks; stock and protected documents unchanged')


if __name__=='__main__':main()
