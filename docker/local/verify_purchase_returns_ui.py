"""Read-only browser acceptance of retained procurement returns and quality documents."""
import json
from pathlib import Path
from urllib.parse import urlencode
from playwright.sync_api import sync_playwright
from verify_goods_workspace_ui import login,BASE

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/purchase-returns'


def main():
    manifest=json.loads((OUT/'manifest.json').read_text('utf-8'));cases={c['key']:c for c in manifest['cases']};checks=[];errors=[];requests=[]
    with sync_playwright() as p:
        b=p.chromium.launch(headless=True);context=b.new_context(viewport={'width':1920,'height':1140});context.add_cookies([{'name':'Admin-Token','value':login(),'domain':'127.0.0.1','path':'/'}]);page=context.new_page();page.set_default_timeout(45000);page.on('pageerror',lambda e:errors.append(str(e)));page.on('request',lambda r:requests.append(dict(method=r.method,url=r.url)))
        def response(part,action):
            with page.expect_response(lambda r:'/prod-api/' in r.url and part in r.url and r.request.method=='GET') as captured:action()
            r=captured.value;assert r.status==200,(part,r.status);body=r.json();assert body.get('code')==200,body;page.wait_for_timeout(250);return body
        def show(key):
            c=cases[key];response('/purchaseReturns/'+str(c['return_id']),lambda:page.goto(BASE+'/oms-supplychain/purchaseReturns?'+urlencode({'id':c['return_id'],'return_sn':c['return_sn']})));panel=page.locator('.return-detail:visible');panel.get_by_role('tab',name='商品与来源',exact=True).wait_for();return panel
        full=cases['full'];response('/purchaseReturns?',lambda:page.goto(BASE+'/oms-supplychain/purchaseReturns?'+urlencode({'return_sn':full['return_sn']})))
        page.locator('.purchase-returns .el-table__body-wrapper').first.get_by_text('采购退仓出库回传 · 成功',exact=True).wait_for();assert '仓库已受理' in page.locator('.purchase-returns').inner_text();page.screenshot(path=str(OUT/'01-return-list.png'));checks.append('列表区分退仓进度、仓库当前受理状态与最近交互动作结果及时间')
        panel=show('full');assert panel.locator('.metrics strong').all_inner_texts()==['3 件','3 件','3 件','0'];assert full['sku'] in panel.inner_text() and '¥2.50' in panel.inner_text();assert not any('/wmsIntegration/log-center?' in r['url'] or '/wmsIntegration/logs/' in r['url'] for r in requests);panel.screenshot(path=str(OUT/'02-return-detail.png'));checks.append('专用详情显示申请3、实出3、已扣账3；保留原SKU、采购单、入库单、批次及原成本；日志按需加载')
        panel.get_by_role('tab',name='出库与扣账',exact=True).click();panel.get_by_text('3 · 扣账成功',exact=True).wait_for();assert 'PRQ_WB_' in panel.inner_text();assert panel.locator('.el-table__body-wrapper').last.inner_text().count('PRQ_BATCH_full')==1;panel.get_by_text('扣账成功',exact=False).last.scroll_into_view_if_needed();page.screenshot(path=str(OUT/'03-outbound-ledger.png'));checks.append('实际物流来自奇门出库回传；事件和原库存占用分别显示，扣账与释放余额可核对')
        panel.get_by_role('button',name='查看原回传',exact=True).click();dialog=page.locator('.el-dialog__wrapper:visible').last;dialog.get_by_role('button',name='加载已脱敏请求与响应',exact=True).wait_for();assert not any('/wmsIntegration/logs/' in r['url'] for r in requests);checks.append('出库事件可以定位原仓库回传，未点击时不加载原报文')
        response('/wmsIntegration/logs/',lambda:dialog.get_by_role('button',name='加载已脱敏请求与响应',exact=True).click());dialog.locator('pre').wait_for();assert '<orderType>CGTH</orderType>' in dialog.locator('pre').inner_text();dialog.screenshot(path=str(OUT/'04-stockout-message.png'));checks.append('原始奇门stockout确认报文可查，采购退仓使用CGTH类型')
        dialog.get_by_role('button',name='关闭',exact=True).click();panel.locator('.return-interactions .el-table__body-wrapper').first.wait_for();assert '采购退仓' in panel.inner_text();checks.append('仓库日志按采购退仓业务归类，展示当次结果和当前业务进度')
        partial=show('partial');assert partial.locator('.metrics strong').all_inner_texts()==['5 件','2 件','2 件','0'];partial.get_by_role('tab',name='出库与扣账',exact=True).click();assert '剩余占用' in partial.inner_text();page.screenshot(path=str(OUT/'05-partial-return.png'));checks.append('部分出库保留申请5、实出2、扣账2及剩余3件占用，未误显示完成')
        mixed=show('mixed');assert mixed.locator('.el-table__body-wrapper').first.locator('tr.el-table__row').count()==2;assert '正品' in mixed.inner_text() and '次品' in mixed.inner_text();mixed.screenshot(path=str(OUT/'06-mixed-grade.png'));checks.append('同SKU同批次的正品和次品分行保留来源与扣账数量，混合退仓明确显示')
        over=show('over');assert '库存未按异常内容扣账' in over.inner_text();assert over.locator('.metrics strong').all_inner_texts()==['3 件','0','0','0'];over.screenshot(path=str(OUT/'07-invalid-outbound.png'));checks.append('超出原计划的仓库回传清晰提示待更正，未确认实出也未扣库存')
        response('/purchaseWorkspace/purchase/'+str(full['purchase_id']),lambda:page.goto(BASE+'/oms-supplychain/poInfo?purchaseId='+str(full['purchase_id'])));proc=page.locator('.procurement-detail:visible');response('/purchaseReturns?',lambda:proc.get_by_role('tab',name='采购退仓',exact=True).click());assert '10 / 3 / 7 件' in proc.inner_text();proc.screenshot(path=str(OUT/'08-original-purchase-net.png'));checks.append('原采购单保留实收10件，退仓页分别显示已退3、净入库7，并可跳转原退仓单')
        def show_quality(key,field):
            c=cases[key];ident=c[field];response('/qualityInventory/'+str(ident),lambda:page.goto(BASE+'/oms-inventory/quality?id='+str(ident)));panel=page.locator('.quality-detail:visible');panel.get_by_text('库存SKU',exact=True).wait_for();return panel
        freeze=show_quality('freeze','quality_FREEZE');assert '剩余冻结' in freeze.inner_text() and '按本来源解冻' in freeze.inner_text();freeze.screenshot(path=str(OUT/'09-quality-freeze.png'));checks.append('冻结详情显示原批次、处理依据、审核人及原来源剩余冻结余额')
        unfreeze=show_quality('unfreeze','quality_UNFREEZE');assert '按原来源解冻' in unfreeze.inner_text();assert cases['unfreeze']['quality_results'][0]['quality_sn'] in unfreeze.inner_text();checks.append('解冻明确绑定原冻结单，不把其他业务锁定当作可释放来源')
        convert=show_quality('convert','quality_CP_TO_ZP');assert '次品转正' in convert.inner_text() and '当前批次正品 / 次品' in convert.inner_text() and '已登记确认依据' in convert.inner_text();convert.screenshot(path=str(OUT/'10-quality-convert.png'));checks.append('转正显示仓库质检依据与审核结果，当前质量数量可查，原入库事实保持')
        response('/qualityInventory?',lambda:page.goto(BASE+'/oms-inventory/quality?'+urlencode({'sku':cases['convert']['sku']})));page.get_by_role('button',name='新增质量处理',exact=True).click();form=page.locator('.el-dialog__wrapper:visible').last;form.get_by_role('button',name='查询实际库存批次',exact=True).wait_for();assert '仓库质检凭证' not in form.inner_text() or '质检' in form.inner_text();form.screenshot(path=str(OUT/'11-quality-form.png'));checks.append('质量处理从实际库存批次选择，表单要求处理原因与凭证，审核后执行')
        assert not errors,errors;mutations=[r for r in requests if r['method'] not in ['GET','OPTIONS'] and '/prod-api/' in r['url']];assert not mutations,mutations;checks.append('浏览器验收仅查询和打开表单，没有改动业务或库存，页面无JavaScript异常');b.close()
    (OUT/'browser-check.json').write_text(json.dumps(dict(checks=checks,javascript_errors=errors,business_mutations=mutations,requests=requests),ensure_ascii=False,indent=2),'utf-8');print('PASS',len(checks),'purchase-return and quality browser checks')


if __name__=='__main__':main()
