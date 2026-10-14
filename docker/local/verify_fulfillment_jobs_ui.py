"""Read-only UI verification of the real central jobs and execution evidence."""
import json
from urllib.parse import urlencode
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE
from configure_fulfillment_jobs import JOBS
OUT=Path(__file__).resolve().parents[2]/'outputs/central-scheduler'

def main():
    token=login()
    try:
        jobs=request('/schedule/job/list?pageNum=1&pageSize=100&invokeTarget=fulfillmentScanTask.',token=token)['rows'];assert len(jobs)==7
        receipt=next(j for j in jobs if j['invokeTarget']=='fulfillmentScanTask.receipts()')
        sales=next(j for j in jobs if j['invokeTarget']=='fulfillmentScanTask.salesDocuments()')
        receipt_rows=request('/schedule/job/log/list?'+urlencode({'pageNum':1,'pageSize':10,'invokeTarget':receipt['invokeTarget']}),token=token)['rows']
        receipt_log=next(row for row in receipt_rows if row.get('executionData'));review_count=json.loads(receipt_log['executionData'])['review_pending']
        sales_rows=request('/schedule/job/log/list?'+urlencode({'pageNum':1,'pageSize':10,'invokeTarget':sales['invokeTarget']}),token=token)['rows']
        sales_log=next(row for row in sales_rows if row.get('executionData') and any(i['reference_sn']=='XS2364cdb8cbfb4f5aa2310d311194adfd' for i in json.loads(row['executionData'])['items']))
        with sync_playwright() as p:
            browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1700,'height':1200});context.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=context.new_page();errors=[];writes=[];page.on('pageerror',lambda e:errors.append(str(e)))
            def readonly(route):
                if route.request.method=='GET':route.continue_()
                else:writes.append(route.request.url.split('?')[0]);route.abort()
            page.route('**/prod-api/**',readonly)
            page.goto(BASE+'/monitor/job?scope=fulfillment',wait_until='networkidle')
            expect(page.locator('.el-table__body-wrapper').first.locator('tbody tr')).to_have_count(7)
            for title,method,cron,remark in JOBS:
                row=page.locator('.el-table__body-wrapper tr').filter(has_text=title).first;expect(row).to_contain_text(cron);expect(row.locator('.el-switch')).to_have_class(__import__('re').compile('is-checked'))
            page.screenshot(path=str(OUT/'central-jobs.png'),full_page=True,animations='disabled')
            # Inspect the original frequency form without saving any change.
            row=page.locator('.el-table__body-wrapper tr').filter(has_text=receipt['jobName']).first;row.locator('button').filter(has_text='修改').first.click();edit=page.locator('.el-dialog:visible');expect(edit.get_by_placeholder('请输入cron执行表达式')).to_have_value(receipt['cronExpression']);edit.locator('.el-dialog__headerbtn').click()
            page.goto(BASE+'/monitor/job-log/index/'+str(receipt['jobId']),wait_until='networkidle')
            first=page.locator('.el-table__body-wrapper tr').filter(has=page.get_by_text(str(receipt_log['jobLogId']),exact=True)).first;expect(first).to_contain_text('待人工核对 '+str(review_count));first.locator('button').filter(has_text='详细').first.click();dialog=page.locator('.el-dialog:visible');expect(dialog).to_contain_text('待人工核对存量 '+str(review_count)+' 条');page.screenshot(path=str(OUT/'receipt-scan-log.png'),full_page=True,animations='disabled')
            page.goto(BASE+'/monitor/job-log/index/'+str(sales['jobId']),wait_until='networkidle')
            page.locator('.el-table__body-wrapper tr').filter(has=page.get_by_text(str(sales_log['jobLogId']),exact=True)).first.locator('button').filter(has_text='详细').first.click();dialog=page.locator('.el-dialog:visible');row=dialog.locator('.el-table__body-wrapper tr').filter(has_text='XS2364cdb8cbfb4f5aa2310d311194adfd');expect(row).to_have_count(1);page.screenshot(path=str(OUT/'sales-sync-log-documents.png'),full_page=True,animations='disabled')
            row.get_by_role('button',name='查看原单',exact=True).click();expect(page).to_have_url(__import__('re').compile('/oms-order/shipments'))
            expect(page.locator('.el-table__body-wrapper').first).to_contain_text('XS2364cdb8cbfb4f5aa2310d311194adfd')
            page.get_by_role('button',name='XS2364cdb8cbfb4f5aa2310d311194adfd',exact=True).first.click();expect(page.locator('.receipt-reviews')).to_contain_text('已确认处理');expect(page.locator('.receipt-reviews')).to_contain_text('本次已扣账')
            assert not errors and not writes,(errors,writes);browser.close()
        (OUT/'ui-verification.json').write_text(json.dumps({'seven_jobs_visible':True,'enabled_switches':True,'frequency_edit_form':True,'structured_statistics_visible':True,'review_stock_state_visible':True,'current_manual_review_count':review_count,'original_document_link':True,'browser_errors':errors,'browser_writes':writes},ensure_ascii=False,indent=2),encoding='utf8');print('PASS scheduler UI: seven jobs, logs, editable frequency and original-document entry; GET-only')
    finally:request('/auth/logout',token=token,method='DELETE')

if __name__=='__main__':main()
