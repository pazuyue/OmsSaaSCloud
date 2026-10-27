"""Fresh-browser checks against retained real return records; no target business actions."""
import csv
import json
from pathlib import Path
from urllib.parse import urlencode
from playwright.sync_api import sync_playwright
from verify_goods_workspace_ui import login

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/return-management';BASE='http://127.0.0.1:8088'


def main():
    case=json.loads((ROOT/'outputs/exchange-return-split/manifest.json').read_text('utf-8'))['cases']['24'];refund=case['final_refund']['refund_sn'];exchange=case['final_exchanges'][1]['exchange_sn'];parcel=case['shared_return']['parcel']['parcel_sn'];token=login();checks=[];errors=[]
    with sync_playwright() as p:
        browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1680,'height':1100},accept_downloads=True);context.add_cookies([{'name':'Admin-Token','value':token,'domain':'127.0.0.1','path':'/'}]);page=context.new_page();page.set_default_timeout(45000);page.on('pageerror',lambda error:errors.append(str(error)))
        def list_response(action):
            with page.expect_response(lambda response:'/order/returns?' in response.url and response.request.method=='GET',timeout=45000) as captured:action()
            response=captured.value;assert response.status==200;value=response.json();assert value['code']==200,value;page.wait_for_timeout(250);return value['data']
        result=list_response(lambda:page.goto(BASE+'/oms-order/returns?'+urlencode({'view':'PARCEL','number':refund})))
        assert result['total']==1 and result['rows'][0]['source_count']==2 and result['rows'][0]['units'][0]['quantity']==2
        page.locator('.return-management').wait_for();assert page.locator('.page-heading h1').count()==1;page.screenshot(path=str(OUT/'returns-mixed-package.png'));checks.append('Refund search shows one complete mixed package with two B units')
        page.locator('.return-management').get_by_role('button',name=parcel,exact=True).click();drawer=page.locator('.el-drawer:visible');drawer.get_by_role('heading',name=parcel,exact=True).wait_for();assert drawer.locator('tr.matched-source').count()==1;assert drawer.locator('tr.matched-source td').first.evaluate('(e)=>getComputedStyle(e).backgroundColor')=='rgb(237, 245, 255)';assert refund in drawer.inner_text() and exchange in drawer.inner_text();drawer.screenshot(path=str(OUT/'returns-mixed-detail.png'));checks.append('Detail retains both owners and highlights the searched refund')
        drawer.locator('.el-drawer__close-btn').click()
        result=list_response(lambda:page.get_by_role('tab',name='退货入库单',exact=True).click());assert result['total']==1 and result['rows'][0]['return_sn']==case['shared_return']['document']['return_sn'];assert result['rows'][0]['units'][0]['posted_quantity']==2;page.screenshot(path=str(OUT/'returns-common-document.png'));checks.append('Actual common warehouse command is a single row')
        result=list_response(lambda:page.get_by_role('tab',name='退货任务',exact=True).click());assert result['total']==1 and result['rows'][0]['members'][0]['sku_sn']==case['target_sku'];checks.append('Refund task shows actual replacement SKU B')
        field=page.get_by_placeholder('完整单号；多个用逗号或空格分隔');field.evaluate("(el,text)=>{const data=new DataTransfer();data.setData('text',text);el.dispatchEvent(new ClipboardEvent('paste',{clipboardData:data,bubbles:true,cancelable:true}))}",refund+'\n'+exchange);assert '\n' not in field.input_value() and refund in field.input_value() and exchange in field.input_value()
        result=list_response(lambda:page.locator('.filters .el-button--primary').click());assert result['total']==2;page.screenshot(path=str(OUT/'returns-two-tasks.png'));checks.append('Multiline number paste finds two tasks without duplicate scope')
        result=list_response(lambda:page.get_by_role('tab',name='退回包裹',exact=True).click());assert result['total']==1;page.get_by_role('button',name='更多筛选',exact=True).click();select=page.locator('.advanced .el-form-item').filter(has_text='合包类型').locator('.el-select');select.click();page.get_by_text('退款 + 换货合包',exact=True).click();result=list_response(lambda:page.locator('.filters .el-button--primary').click());assert result['total']==1;checks.append('Mixed package filter retains full source quantity')
        with page.expect_download(timeout=45000) as download:page.get_by_role('button',name='导出查询结果',exact=True).click()
        path=OUT/'returns-mixed-export.csv';download.value.save_as(str(path));exported=list(csv.reader(path.read_text('utf-8-sig').splitlines()));assert len(exported)==2 and '退款 1 单 + 换货 1 单' in exported[1] and '2 件' in exported[1];checks.append('Export preserves current filters and complete package amounts')
        partial=json.loads((ROOT/'outputs/exchange-partial-continuous/manifest.json').read_text('utf-8'))['cases']['19'];result=list_response(lambda:page.goto(BASE+'/oms-order/returns?'+urlencode({'view':'TASK','number':partial['claims'][0]['exchange_sn']})));assert result['total']==1 and result['rows'][0]['stage']=='PARTIAL';page.screenshot(path=str(OUT/'returns-partial-receipt.png'));checks.append('Unreceived remainder remains visible as partial receipt')
        assert not errors,errors;browser.close()
    (OUT/'browser-check.json').write_text(json.dumps({'checks':checks,'javascript_errors':errors,'export_rows':len(exported)-1},ensure_ascii=False,indent=2),'utf-8');print('PASS',len(checks),'browser checks; zero JS errors; filtered CSV saved')


if __name__=='__main__':main()
