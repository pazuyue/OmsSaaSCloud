"""Read-only browser acceptance against retained procurement scenarios."""
import json
import openpyxl
from pathlib import Path
from urllib.parse import urlencode
from playwright.sync_api import sync_playwright
from verify_goods_workspace_ui import login,BASE

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/supplychain-iteration'


def main():
    manifest=json.loads((OUT/'manifest.json').read_text('utf-8'));cases={c['key']:c for c in manifest['cases']};checks=[];errors=[];requests=[]
    with sync_playwright() as p:
        browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1920,'height':1140},accept_downloads=True)
        context.add_cookies([{'name':'Admin-Token','value':login(),'domain':'127.0.0.1','path':'/'}]);page=context.new_page();page.set_default_timeout(45000)
        page.on('pageerror',lambda e:errors.append(str(e)));page.on('request',lambda r:requests.append(dict(method=r.method,url=r.url)))
        def response(part,action):
            with page.expect_response(lambda r:part in r.url and r.request.method=='GET') as captured:action()
            r=captured.value;assert r.status==200,(part,r.status);body=r.json();assert body.get('code')==200,body;page.wait_for_timeout(250);return body
        def goto(key,kind='purchase',extra=None):
            c=cases[key];route='poInfo' if kind=='purchase' else 'arrivals';params={('purchaseId' if kind=='purchase' else 'receiptId'):c[kind+'_id'],('poSn' if kind=='purchase' else 'noSn'):c[kind+'_sn']};params.update(extra or {})
            response('/purchaseWorkspace/'+kind+'/'+str(c[kind+'_id']),lambda:page.goto(BASE+'/oms-supplychain/'+route+'?'+urlencode(params)))
            detail=page.locator('.procurement-detail:visible');detail.get_by_role('tab',name='商品与数量',exact=True).wait_for();return detail
        detail=goto('scheduled');assert detail.locator('.metrics strong').all_inner_texts()[:3]==['100 件','60 件','40 件'];assert '待收 60 件' in detail.inner_text()
        assert not any('/wmsIntegration/log-center?' in r['url'] or '/wmsIntegration/logs/' in r['url'] for r in requests);checks.append('采购详情区分计划100、有效安排60、未安排40；默认不加载日志或原报文')
        detail.screenshot(path=str(OUT/'01-purchase-plan.png'));detail.get_by_role('tab',name='到货批次（1）',exact=True).click()
        response('/purchaseWorkspace/receipt/',lambda:detail.get_by_role('button',name=cases['scheduled']['receipt_sn'],exact=True).click())
        detail=page.locator('.procurement-detail:visible');assert '审核本批到货' in detail.inner_text();assert detail.locator('.metrics strong').first.inner_text()=='60 件';checks.append('采购可进入独立到货详情，待审核批次保留60件待收，尚未下发仓库')
        detail=goto('full','receipt');assert detail.locator('.metrics strong').all_inner_texts()[:3]==['5 件','5 件','5 件'];checks.append('独立到货入口显示本批安排5、仓库实收5、库存入账5')
        response('/batches?',lambda:detail.get_by_role('tab',name='实收与入账',exact=True).click());detail.get_by_text('入账成功',exact=True).last.wait_for()
        assert cases['full']['sku'] in detail.inner_text() and 'WMS_RECEIPT_' in detail.inner_text() and '原采购单价 ¥2.5' in detail.inner_text();detail.screenshot(path=str(OUT/'02-receipt-stock.png'));detail.get_by_text('原采购单价 ¥2.5',exact=True).scroll_into_view_if_needed();page.screenshot(path=str(OUT/'02b-stock-ledger.png'));checks.append('实收页展示实际SKU、原采购成本、批次、入账结果及库存流水关联号')
        detail.get_by_role('button',name='查看回传',exact=True).click();dialog=page.locator('.el-dialog__wrapper:visible').last;dialog.get_by_role('button',name='加载已脱敏请求与响应',exact=True).wait_for()
        assert not any('/wmsIntegration/logs/' in r['url'] for r in requests);checks.append('入账记录可定位原回传，原始请求响应仍按需加载')
        response('/wmsIntegration/logs/',lambda:dialog.get_by_role('button',name='加载已脱敏请求与响应',exact=True).click());dialog.locator('pre').wait_for();assert cases['full']['ticket_sn'] in dialog.inner_text();dialog.screenshot(path=str(OUT/'03-warehouse-callback.png'));checks.append('仓库交互详情可加载脱敏原报文并保留当次动作与结果')
        dialog.get_by_role('button',name='关闭',exact=True).click()
        detail=goto('partial');assert '待收 6 件' in detail.inner_text();assert detail.locator('.metrics strong').all_inner_texts()[3:6]==['4 件','4 件','0'];detail.screenshot(path=str(OUT/'04-partial.png'));checks.append('部分实收4件保留6件待收，不提前判定最终短收')
        detail=goto('short');assert detail.locator('.metrics strong').all_inner_texts()[-1]=='2 件';response('/purchaseWorkspace/differences?',lambda:detail.get_by_role('tab',name='收货差异',exact=True).click());detail.locator('.difference-panel').get_by_text('最终短收',exact=True).wait_for();detail.get_by_role('button',name='核对并处理',exact=True).click()
        dialog=page.locator('.el-dialog__wrapper:visible').last;dialog.get_by_text('实际回传SKU',exact=True).wait_for();assert cases['short']['sku'] in dialog.inner_text();assert '本批原计划' in dialog.inner_text();dialog.screenshot(path=str(OUT/'05-short-difference.png'));checks.append('最终短收显示2件，可核对本批原计划、原消息、原SKU及可选处理方式')
        dialog.get_by_role('button',name='关闭',exact=True).click()
        over=cases['over'];difference=over['differences'][0];response('/purchaseWorkspace/operations?',lambda:page.goto(BASE+'/oms-supplychain/operations?'+urlencode({'number':over['purchase_sn']})))
        # The workbench starts with current defaults; an exact number is entered explicitly.
        page.locator('.supplychain-operations .el-form-item').filter(has_text='单号').locator('input').fill(over['purchase_sn'])
        result=response('/purchaseWorkspace/operations?',lambda:page.get_by_role('button',name='查询',exact=True).click())['data'];assert any(r['issue']=='DIFFERENCE' for r in result['rows']);assert '超收：' in page.locator('.supplychain-operations').inner_text();page.screenshot(path=str(OUT/'06-workbench.png'));checks.append('统一待办查询能定位超收差异，影响数量及原因可见')
        response('/purchaseWorkspace/receipt/',lambda:page.locator('.supplychain-operations tr.el-table__row').filter(has_text='超收：').get_by_role('button',name='查看并处理').click())
        page.locator('.difference-panel:visible').wait_for();dialog=page.locator('.el-dialog__wrapper:visible').last;dialog.get_by_text('超收 · 待处理',exact=True).wait_for();assert '本次异常回传尚未确认为实收或库存' in dialog.inner_text();checks.append('待办直达对应到货差异，不把仓库超收回传展示为已入库')
        dialog.get_by_role('button',name='关闭',exact=True).click()
        fail=cases['dispatch_fail'];response('/purchaseWorkspace/receipt/list?',lambda:page.goto(BASE+'/oms-supplychain/arrivals?'+urlencode({'noSn':fail['receipt_sn']})))
        page.locator('.interaction-cell').get_by_role('button',name='下发失败',exact=True).wait_for();page.screenshot(path=str(OUT/'07-arrival-failed.png'));checks.append('到货列表显示真实下发失败和最近失败原因，不冒充仓库已受理')
        with page.expect_download() as download:page.get_by_text('导出筛选结果',exact=True).click()
        target=OUT/'arrival-export.xlsx';download.value.save_as(str(target));rows=list(openpyxl.load_workbook(target,read_only=True).active.values);assert len(rows)==2 and '最近交互结果' in rows[0] and '失败' in rows[1];checks.append('到货导出保留正确到货字段、仓库状态和最近交互结果')
        response('/purchaseWorkspace/ticket/list?',lambda:page.goto(BASE+'/oms-supplychain/wmsTickets?'+urlencode({'sn':cases['full']['ticket_sn']})))
        response('/purchaseWorkspace/line/list?',lambda:page.get_by_role('button',name='商品明细',exact=True).click())
        response('/purchaseWorkspace/ticket/'+str(cases['full']['ticket_id']),lambda:page.locator('.interaction-cell').get_by_role('button',name='查看交互',exact=True).click());checks.append('出入库商品视图的仓库交互定位原CG执行单，不把商品行ID当成仓单ID')
        assert not errors,errors
        mutations=[r for r in requests if r['method'] not in ['GET','OPTIONS'] and '/prod-api/' in r['url'] and '/export' not in r['url']]
        assert not mutations,mutations;checks.append('浏览器验收未更改单据或库存，页面无JavaScript异常');browser.close()
    (OUT/'browser-check.json').write_text(json.dumps(dict(checks=checks,javascript_errors=errors,business_mutations=mutations,requests=requests),ensure_ascii=False,indent=2),'utf-8');print('PASS',len(checks),'browser checks')


if __name__=='__main__':main()
