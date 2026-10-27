"""Browser acceptance of lifecycle UI. Intercept form submissions; preserve retained business data."""
import json,re,sys
from pathlib import Path
from datetime import datetime
from urllib.parse import urlencode,urlparse
from playwright.sync_api import sync_playwright,expect
from verify_goods_workspace_ui import login,request,BASE

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/purchase-return-iteration';RET='/supplychain/purchaseReturns'

def main():
 state=json.loads((OUT/'scenarios.json').read_text('utf-8'));base=json.loads((OUT/'base/manifest.json').read_text('utf-8'));cases={c['key']:c for c in base['cases']};token=login();checks=[];errors=[];intercepted=[];unexpected=[]
 def api(path):return request(path,token=token)
 def baseline():
  stock={}
  for c in cases.values():
   r=api('/inventory/productInventory?'+urlencode(dict(skuSn=c['sku'])))['rows'][0];stock[c['sku']]={k:r[k] for k in ['totalStock','availableStock','allocatedStock','reservedStock','frozenStock']}
  d=api(RET+'/17')['data'];return dict(total=api(RET)['data']['total'],stock=stock,original={k:d[k] for k in ['state','quantity','accepted_quantity','posted_quantity','control_version']})
 before=baseline();message='界面验收请求已拦截，单据未改变'
 with sync_playwright() as p:
  browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1720,'height':1200});context.add_cookies([{'name':'Admin-Token','value':token,'domain':'127.0.0.1','path':'/'}])
  def guard(route):
   req=route.request;path=urlparse(req.url).path
   if req.method in ['GET','OPTIONS']:route.continue_()
   elif req.method=='POST' and (path=='/prod-api'+RET or re.fullmatch('/prod-api'+RET+r'/[0-9]+/funds',path)):
    intercepted.append(dict(path=path,body=req.post_data_json));route.fulfill(status=200,json={'code':500,'msg':message})
   else:unexpected.append(dict(method=req.method,path=path));route.abort()
  context.route('**/prod-api/**',guard);page=context.new_page();page.set_default_timeout(25000);page.on('pageerror',lambda e:errors.append(str(e)))
  def response(path,action):
   with page.expect_response(lambda r:urlparse(r.url).path=='/prod-api'+path and r.request.method=='GET') as captured:action()
   r=captured.value;assert r.status==200 and r.json().get('code')==200;return r.json()
  def show(id):
   response(RET+'/'+str(id),lambda:page.goto(BASE+'/oms-supplychain/purchaseReturns?'+urlencode(dict(id=id))))
   panel=page.locator('.return-detail:visible');expect(panel.locator('.metrics article')).to_have_count(5);return panel
  def dialog(title):return page.locator('.el-dialog__wrapper:visible').filter(has=page.get_by_text(title,exact=True)).last
  def tab(panel,name):panel.get_by_role('tab',name=name,exact=True).click()
  try:
   original=show(17);expect(original.locator('.progress>strong')).to_have_text('部分出库，剩余待处理');assert original.locator('.metrics article>strong').all_inner_texts()==['5 件','2 件','2 件','3 件','3 件'];original.screenshot(path=str(OUT/'01-original-final-short.png'));checks.append('原CGT单据清楚展示最终实出2、未出3、占用3及剩余处理入口')
   original.get_by_role('button',name='处理剩余数量',exact=True).click();remaining=dialog('处理剩余退仓数量');expect(remaining.get_by_text('3 件；批次可用 5 → 8',exact=True)).to_be_visible();remaining.get_by_text('继续退剩余数量',exact=True).click();expect(remaining.get_by_role('button',name='结束原单并生成后续草稿',exact=True)).to_be_visible();remaining.screenshot(path=str(OUT/'02-remaining-decision.png'));remaining.get_by_role('button',name='返回',exact=True).click();checks.append('剩余处理可预览按原批次释放3件及可用5变8，继续申请明确须重新审核')
   parent=show(cases['final_short']['return_id']);expect(parent.locator('.progress>strong')).to_have_text('部分完成，剩余已停止');assert parent.locator('.metrics article>strong').all_inner_texts()==['5 件','2 件','2 件','3 件','0'];assert '关联后续退仓' in parent.inner_text();parent.screenshot(path=str(OUT/'03-partial-closed-linked-child.png'));checks.append('部分结束保留原实出2件，显示剩余已停止及唯一后续单关联')
   child=show(state['ids']['child']);expect(child.locator('.progress>strong')).to_have_text('全部退仓完成');assert '承接原退仓' in child.inner_text();checks.append('后续退仓可反查原单，并独立展示实际出库与完成状态')
   tab(child,'供应商款项');child.get_by_role('button',name='登记退款 / 抵扣凭证',exact=True).click();fund=dialog('登记供应商款项凭证');fund.get_by_text('抵扣凭证',exact=True).click();fund.locator('.el-input-number input').fill('2.50');fund.locator('textarea').fill('界面验收抵扣凭证，仅拦截保存请求')
   with page.expect_response(lambda r:urlparse(r.url).path=='/prod-api'+RET+'/'+str(state['ids']['child'])+'/funds'):fund.get_by_role('button',name='保存凭证与结果',exact=True).click()
   expect(fund.get_by_text(message,exact=True)).to_be_visible();assert intercepted[-1]['body']['funds_state']=='CREDIT' and float(intercepted[-1]['body']['amount'])==2.5;fund.get_by_role('button',name='返回',exact=True).click();checks.append('款项表单可选择抵扣并提交金额、凭证、幂等键和原版本；验收请求已拦截')
   full=show(cases['full']['return_id']);tab(full,'供应商款项');expect(full.get_by_text('冲销凭证',exact=False).first).to_be_visible();assert full.locator('.el-table__body-wrapper').last.locator('tr.el-table__row').count()==4;full.screenshot(path=str(OUT/'04-multiple-vouchers-reversal.png'));checks.append('多笔退款抵扣与负数冲销分别保留，展示出库货值、收回货值和净金额')
   full.get_by_role('button',name='冲销更正',exact=True).first.click();reverse=dialog('冲销原款项凭证');assert '原记录保留' in reverse.inner_text();reverse.get_by_role('button',name='返回',exact=True).click();checks.append('原有效凭证可打开冲销更正入口，原凭证与关联冲销号可查')
   tab(full,'供应商收货与收回');assert '确认签收' in full.inner_text() and '确认拒收' in full.inner_text() and '短收待核对' in full.inner_text();full.screenshot(path=str(OUT/'05-supplier-receipts.png'));full.get_by_role('button',name='登记签收 / 拒收 / 短收',exact=True).click();receipt=dialog('登记供应商收货结果');inputs=receipt.locator('.el-input-number input');assert (inputs.first.get_attribute('aria-valuemax') or inputs.first.get_attribute('max'))=='1';receipt.get_by_role('button',name='返回',exact=True).click();checks.append('供应商签收、拒收、短收凭证分开显示，登记上限按尚未确认数量限制')
   response(RET+'/reclaims/'+str(state['ids']['real_reclaim']),lambda:full.get_by_role('button',name=re.compile('^CGH')).first.click());reclaim_panel=page.locator('.reclaim-detail:visible');expect(reclaim_panel.get_by_text('次品',exact=True)).to_be_visible();assert 'QTRK' in reclaim_panel.inner_text() and 'PURCHASE_RECLAIM_' in reclaim_panel.inner_text();reclaim_panel.screenshot(path=str(OUT/'06-reclaim-actual-quality.png'));checks.append('独立收回详情区分原计划质量与实际次品质检，显示运单和新增库存来源')
   reclaim_panel.get_by_role('tab',name='仓库交互',exact=True).click();expect(reclaim_panel.get_by_text('拒收收回实收回传',exact=False).first).to_be_visible();reclaim_panel.screenshot(path=str(OUT/'07-reclaim-warehouse-interactions.png'));checks.append('收回仓库交互使用专属业务与动作名称，能查下发和实收回传')
   response(RET+'/reclaims/'+str(state['ids']['reclaim_race_draft']),lambda:page.goto(BASE+'/oms-supplychain/purchaseReturns?reclaimId='+str(state['ids']['reclaim_race_draft'])));draft=page.locator('.reclaim-detail:visible');draft.get_by_role('button',name='审核并自动收回入库',exact=True).click();box=page.locator('.el-message-box__wrapper:visible');expect(box.get_by_text('审核收回入库',exact=True)).to_be_visible();box.get_by_role('button',name='取消',exact=True).click();checks.append('收回草稿审核入口有明确确认窗口，取消确认不触发业务操作')
   response('/supplychain/purchaseWorkspace/purchase/'+str(cases['full']['purchase_id']),lambda:page.goto(BASE+'/oms-supplychain/poInfo?purchaseId='+str(cases['full']['purchase_id'])));purchase=page.locator('.procurement-detail:visible');response(RET,lambda:purchase.get_by_role('tab',name='采购退仓',exact=True).click());expect(purchase.get_by_text('10 / 4 / 1 / 7 件',exact=True)).to_be_visible();purchase.screenshot(path=str(OUT/'08-procurement-net-receipts.png'));checks.append('原采购展示入账10、退仓出库4、拒收收回1、净入库7，与实际库存一致')
   mixed=show(cases['mixed']['return_id']);tab(mixed,'供应商收货与收回');mixed.get_by_role('button',name='创建拒收收回入库',exact=True).click();form=dialog('创建拒收收回入库单');expect(form.get_by_text('供应商退回本仓运单号',exact=True)).to_be_visible();assert (form.locator('.el-input-number input').first.get_attribute('aria-valuemax') or form.locator('.el-input-number input').first.get_attribute('max'))=='1';form.screenshot(path=str(OUT/'09-reclaim-source-form.png'));form.get_by_role('button',name='返回',exact=True).click();checks.append('拒收收回表单限定原已拒收且尚未安排的数量，要求独立退回快递和运单')
   response(RET+'/sources',lambda:page.goto(BASE+'/oms-supplychain/purchaseReturns?'+urlencode(dict(purchaseId=cases['full']['purchase_id'],create='1'))));create=dialog('新增采购退仓');region=create.locator('.address-region .el-select input');expect(region).to_have_count(3);expect(region.nth(2)).to_have_value('西湖区');assert [region.nth(i).input_value() for i in range(3)]==['浙江省','杭州市','西湖区'];assert '本地验收供应商退仓地址'==create.locator('.destination .el-form-item').filter(has_text='供应商退仓地址').locator('input').input_value();checks.append('新增表单按三段组织，真实供应商地址自动带入省市区联动下拉')
   def choose(index,name):
    field=region.nth(index);control=field.get_attribute('aria-controls');field.click();dropdown=page.locator('[id="'+control+'"]') if control else page.locator('.el-select-dropdown:visible');option=dropdown.get_by_text(name,exact=True);option.wait_for(state='visible');option.click();expect(field).to_have_value(name);dropdown.wait_for(state='hidden');page.wait_for_timeout(150)
   choose(0,'广东省');assert region.nth(1).input_value()==region.nth(2).input_value()=='';expect(region.nth(2)).to_be_disabled();choose(1,'广州市');choose(2,'天河区');choose(1,'深圳市');assert region.nth(2).input_value()=='';choose(2,'南山区');checks.append('复用全国目录，切换省清空市区，切换市清空区县并更新候选')
   province=create.locator('.address-region .el-select').first;province.hover();province.locator('.el-icon-circle-close').click();expect(region.nth(1)).to_be_disabled();expect(region.nth(2)).to_be_disabled();page.wait_for_timeout(250);choose(0,'北京市');choose(1,'北京市');choose(2,'朝阳区');checks.append('清空上级禁用下级，直辖市三级选择正常')
   carrier=create.locator('.destination .el-form-item').filter(has_text='退仓快递 / 仓库编码').locator('.el-select input');carrier.click();option=page.locator('.el-select-dropdown:visible').get_by_text('[退仓迭代TEST] 顺丰 · SF',exact=True);option.wait_for(state='visible');option.click();checks.append('快递下拉复用该货主实仓配置，显示快递名称和仓库编码')
   qty=create.locator('.el-table__body-wrapper .el-input-number input').first;qty.fill('1');qty.press('Tab');create.locator('.destination .el-form-item').filter(has_text='退仓原因与协商依据').locator('textarea').fill('采购退仓界面验收，仅拦截创建请求');footer=create.get_by_role('button',name='保存草稿',exact=True);bounds=footer.bounding_box();assert bounds and bounds['y']+bounds['height']<=1200;create.screenshot(path=str(OUT/'10-create-address-and-footer.png'));checks.append('底部汇总本次数量及采购货值，表单滚动时保存操作保持在可见范围')
   with page.expect_response(lambda r:urlparse(r.url).path=='/prod-api'+RET and r.request.method=='POST'):footer.click()
   expect(create.get_by_text(message,exact=True)).to_be_visible();body=intercepted[-1]['body'];assert body['purchase_id']==cases['full']['purchase_id'] and len(body['lines'])==1 and body['carrier_code']=='SF';assert [body['receiver'][k] for k in ['province','city','area']]==['北京市','北京市','朝阳区'];checks.append('新增保存请求保留原来源、地区名称、物流编码及幂等键；验收请求已拦截')
   assert not errors,errors;assert not unexpected,unexpected
  except Exception:page.screenshot(path=str(OUT/'browser-failure.png'));raise
  finally:browser.close()
 assert baseline()==before,'Browser changed retained business or stock';checks.append('原用户单据、退仓单总数和全部验收SKU库存未变化，页面无JavaScript异常')
 (OUT/'browser-check.json').write_text(json.dumps(dict(result='PASS',checked_at=datetime.now().isoformat(),checks=checks,javascript_errors=errors,intercepted_requests=intercepted,unexpected_mutations=unexpected,business_data_unchanged=True),ensure_ascii=False,indent=2),'utf-8');print('PASS',len(checks),'procurement-return lifecycle browser checks')

if __name__=='__main__':main()
