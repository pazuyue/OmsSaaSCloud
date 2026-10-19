"""Browser acceptance of the company-scoped message workspace."""
import json
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,BASE
OUT=Path(__file__).resolve().parents[2]/'outputs/messaging'
def main():
 c=json.loads((OUT/'case.json').read_text('utf-8'));token=login()
 with sync_playwright() as p:
  browser=p.chromium.launch(headless=True);ctx=browser.new_context(viewport={'width':1680,'height':1050});ctx.add_cookies([{'name':'Admin-Token','value':token,'url':BASE}]);page=ctx.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)));page.goto(BASE+'/monitor/messages',wait_until='networkidle');expect(page.locator('.messages h2')).to_have_text('消息任务')
  field=page.get_by_placeholder('业务单号 / 消息号 / 追踪号');field.fill('PRODUCTPULL'+str(c['normal_task']));page.get_by_role('button',name='查询',exact=True).click();row=page.locator('.el-table__body-wrapper tr').filter(has_text='PRODUCTPULL'+str(c['normal_task'])).first;expect(row).to_contain_text('处理完成');page.screenshot(path=str(OUT/'messages-channel.png'),full_page=True,animations='disabled');page.locator('.el-table__fixed-right').get_by_role('button',name='详情 / 链路',exact=True).first.click();drawer=page.locator('.el-drawer:visible');expect(drawer).to_contain_text('消息代理已确认接收');drawer.get_by_text('消息信封 · 仅业务标识',exact=True).click();expect(drawer.locator('pre')).to_contain_text(c['normal_event']);page.screenshot(path=str(OUT/'message-detail.png'),full_page=True,animations='disabled');drawer.get_by_role('button',name='打开业务单据',exact=True).click();expect(page).to_have_url(__import__('re').compile('/oms-channel/platformProducts.*channelId='+str(c['channel_id'])))
  page.goto(BASE+'/monitor/messages',wait_until='networkidle');page.get_by_role('tab',name='订单服务 · 订单、售后与出库',exact=True).click();field=page.get_by_placeholder('业务单号 / 消息号 / 追踪号');field.fill(c['sales_document']);page.get_by_role('button',name='查询',exact=True).click();row=page.locator('.el-table__body-wrapper tr').filter(has_text=c['sales_document']).first;expect(row).to_contain_text('处理完成',timeout=20000);page.locator('.el-table__fixed-right').get_by_role('button',name='详情 / 链路',exact=True).first.click();drawer=page.locator('.el-drawer:visible');expect(drawer).to_contain_text('销售出库单据已同步');page.screenshot(path=str(OUT/'messages-sales.png'),full_page=True,animations='disabled');drawer.get_by_role('button',name='打开业务单据',exact=True).click();expect(page.get_by_placeholder('完整发货单、OMS 或平台单号')).to_have_value(c['sales_document']);assert not errors,errors;browser.close()
 print('PASS message UI: service scopes, filters, result timeline, envelope and business links')
if __name__=='__main__':main()
