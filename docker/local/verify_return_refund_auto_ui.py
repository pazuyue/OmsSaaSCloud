"""Read-only retained-case UI/stock acceptance, including exact gift ownership and merchant evidence."""
import json
import re
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from verify_return_refund_auto import Acceptance,OUT,BASE,dump

expect.set_options(timeout=20000)

def main():
    run=Acceptance();result=json.loads((OUT/'results.json').read_text('utf-8'))
    assert len(result['rows'])==10 and all(r['status']=='PASS' for r in result['rows']),[(r['case'],r['status']) for r in result['rows']]
    cases=run.data['cases'];stock={k:run.snapshot(c) for k,c in cases.items()}
    refs=[r for c in cases.values() for r in c['refunds'].values()]
    before={r['id']:run.api('/order/refunds/business/'+str(r['id'])) for r in refs}
    errors=[];writes=[];checks=[]
    with sync_playwright() as p:
        browser=p.chromium.launch(headless=True);ctx=browser.new_context(viewport={'width':1640,'height':1080})
        ctx.add_cookies([{'name':'Admin-Token','value':run.token,'url':BASE}])
        def page():
            tab=ctx.new_page();tab.on('pageerror',lambda e:errors.append(str(e)))
            tab.on('request',lambda q:writes.append(q.method+' '+q.url) if '/prod-api/' in q.url and q.method not in ['GET','HEAD','OPTIONS'] else None)
            return tab
        def detail(key,role='target'):
            tab=page();ref=cases[key]['refunds'][role];tab.goto(ref['url'],wait_until='networkidle');dialog=tab.get_by_role('dialog',name=re.compile('^业务退款详情'))
            expect(dialog.locator('.refund-identity-main > strong')).to_have_text(ref['refund_sn'])
            return tab,dialog
        def shot(tab,name):tab.screenshot(path=str(OUT/name),animations='disabled')
        tab,d=detail('01');expect(d.locator('.refund-progress')).to_contain_text('本退款处理完成');expect(d.locator('.application-money-summary strong')).to_have_text(['¥100.00','¥100.00','¥100.00']);shot(tab,'01-completed.png');tab.close()
        for role,letters in [('target1','EF'),('target2','GH')]:
            tab,d=detail('07',role);box=d.locator('.refund-logistics');expect(box).to_be_visible();expect(box.locator('.own-logistics-quantities .el-table__body-wrapper > table > tbody > tr')).to_have_count(4)
            for letter in letters:expect(box.locator('.incoming-package')).to_contain_text(cases['07']['prefix']+letter)
            for letter in ('GH' if letters=='EF' else 'EF'):expect(box.locator('.incoming-package')).not_to_contain_text(cases['07']['prefix']+letter)
            box.locator('.incoming-package').scroll_into_view_if_needed();shot(tab,'07-'+role+'-correct-gifts.png');tab.close()
        checks.append('Fresh composite case: each refund owns exactly its purchased components and corresponding gifted kit/bag components')
        tab,d=detail('05');expect(d.locator('.refund-progress')).to_contain_text('本退款处理完成');box=d.locator('.refund-logistics');expect(box).to_contain_text(cases['05']['initial_return_waybill']);expect(box).to_contain_text(cases['05']['supplement_waybill']);expect(box).to_contain_text('当前沿用原包裹');box.locator('.logistics-heading').scroll_into_view_if_needed();shot(tab,'05-supplement-source-and-original-thk.png');tab.close()
        checks.append('Fresh missing-gift case: new supplemental logistics retained, original parcel not overwritten, actual receipt and stock independently completed')
        tab,d=detail('08');expect(d.locator('.application-money-summary strong')).to_have_text(['¥100.00','¥80.00','¥80.00']);expect(d.locator('.basis-values strong')).to_have_text(['¥20.00','¥80.00','¥100.00','¥0.00']);shot(tab,'08-prior20-target80.png');tab.close()
        tab,d=detail('09');expect(d.locator('.application-money-summary strong')).to_have_text(['¥100.00','¥100.00','¥0.00']);expect(d.locator('.refund-amount-basis')).to_contain_text('80.00');shot(tab,'09-over-remaining-protected.png');tab.close()
        tab,d=detail('10');expect(d.locator('.refund-progress')).to_contain_text('本退款处理完成');d.get_by_role('tab',name='平台处理与资金',exact=True).click();shot(tab,'10-pay-response-loss-reconciled.png');tab.close()
        checks.append('Retained money cases: difference20 + return80 totals100, over-remaining100 stays unpaid, response-loss case completes without a second payment')
        tab=page();tab.goto(BASE+'/oms-order/afterSaleRules/settings?channelId='+str(cases['08']['channel_id'])+'&tab=automation',wait_until='networkidle')
        expect(tab.locator('.automation-form')).to_contain_text('退货退款自动确认方案');expect(tab.locator('.effect-panel')).to_contain_text('退货退款自动确认方案')
        tab.get_by_role('button',name='登记 / 查看差价业务授权',exact=True).click();dialog=tab.get_by_role('dialog',name='差价业务授权',exact=True);expect(dialog).to_be_visible();expect(dialog.locator('.el-table')).to_contain_text(cases['08']['refunds']['prior']['refund_id']);expect(dialog.locator('.el-table')).to_contain_text('已采用');expect(dialog.locator('.el-table')).to_contain_text('admin');expect(dialog.get_by_role('button',name='撤销',exact=True)).to_have_count(0);shot(tab,'08-merchant-authorization-separate-from-auto-review.png');tab.close()
        checks.append('New settings and consumed exact-refund authorization are visible; merchant authorization remains distinct from automatic refund review/payment')
        browser.close()
    after={r['id']:run.api('/order/refunds/business/'+str(r['id'])) for r in refs}
    for id in before:
        for field in ['state','review_state','review_by','purpose','goods_state','funds_state']:assert before[id][field]==after[id][field],(id,field)
    assert stock=={k:run.snapshot(c) for k,c in cases.items()}
    assert not errors,errors
    assert not writes,writes
    dump(OUT/'ui-verification.json',{'checks':checks,'page_errors':errors,'write_requests':writes,'stock_and_quotas_unchanged':True,'refund_states_unchanged':True})
    print('PASS retained return-refund UI, exact gift ownership, authorization evidence, no writes or stock changes')

if __name__=='__main__':main()
