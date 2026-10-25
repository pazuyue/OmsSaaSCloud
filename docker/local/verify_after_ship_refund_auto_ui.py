"""Read-only UI verification and an index of retained automatic refund test data."""
import html
import json
import re
from collections import Counter
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from verify_after_ship_refund_auto_cases import Run, OUT, BASE, ORDER
from test_multi_virtual_shipping import sql

SCENES = {
 '01':'A一件实付100元，实际出库并模拟买家收货；申请仅退款10元、保留商品，期望命中20元小额免退规则。',
 '02A':'独立订单A一件实付100元，模拟已收货，申请仅退款20元，验证小额门槛包含等于20元。',
 '02B':'另一张独立订单A一件实付100元，模拟已收货，申请仅退款20.01元，验证超出小额门槛转人工。',
 '03':'A一件实付100元，已出库并模拟收货；前置纯资金退款10元已完成，再申请仅退款15元，期望识别累计25元超出20元免退门槛。',
 '04':'A一件实付100元，TEST仓库明确接单但尚未出库；申请仅退款100元取消购买，验证自动仓库撤回、取消需求、释放库存及平台资金处理。',
 '05':'A一件实付100元，在准备截单竞争分支时已经实际出库，再申请仅退款100元取消购买。系统在确认用途前停止，真正截单与出库并发分支未到达。',
 '06':'A一件实付100元，已实际出库、模拟买家未收到；申请仅退款100元取消购买，期望自动形成物流拦截方案并沿退回结果推进。',
 '07':'A两件实付200元，已实际出库并模拟买家收货；平台仅退款200元，目标退回两件。期望自动建安排、下发及分批实收入账；当前停在审核前，回执步骤未到达。',
 '08':'A三件实付300元，两个虚拟仓分别安排1和2件；第一仓已真实回传出库1件，第二仓明确接单未出2件。申请仅退款300元，期望自动分项截单和退回。本次两个虚拟仓属于同一实体仓。',
 '09':'A一件实付100元，赠G一件，两者都已实际出库。前置差价退款20元已完成且货物保留；再申请仅退款80元取消购买，期望按剩余金额退款并处理主品和关联赠品。',
 '10':'A一件实付100元，已出库，前置差价20元已完成；再次申请仅退款100元，期望识别剩余可退80元并阻止累计超额。'
}


def main():
    r=Run();r.ready();m=r.data
    assert len(m['cases'])==11 and all(c.get('finished') for c in m['cases'].values())
    checks=[];stocks={}
    for key,c in m['cases'].items():
        current=r.snapshot(c);stocks[key]=current
        bysku={row[0]:list(map(int,row[1:])) for row in current['company']}
        outbound=0 if key=='04' else 1 if key=='08' else c['quantity']
        total,available,reserved,allocated=bysku[c['sku']]
        assert total==100-outbound,(key,bysku)
        assert reserved==(2 if key=='08' else 0),(key,bysku)
        assert allocated==0,(key,bysku)
        assert available==total-reserved,(key,bysku)
        if key=='09':assert bysku[c['gift']]==[99,99,0,0]
        if key!='04':assert current==c['stock_before_refunds'],key+' unexpected stock change'
        for ref in c['refunds'].values():
            b=r.api('/order/refunds/business/'+str(ref['id']))
            if ref['setup_only']:
                assert b['funds_state']=='SUCCESS' and b['goods_state']=='DONE' and b['goods_action']=='KEEP'
            else:
                assert len(r.api('/order/refunds/business/'+str(ref['id'])+'/goods')['plans'])==0
                assert b['review_state'] in ['PENDING','STALE']
                if key!='04':assert not sql(f"SELECT id FROM oms_refund_operation WHERE refund_id={ref['id']} AND action='PAY'",ORDER)
                if key=='04':
                    assert b['goods_state']=='DONE' and b['platform']['source_state']=='CONFLICT'
                    ops=sql(f"SELECT action,state FROM oms_refund_operation WHERE refund_id={ref['id']} ORDER BY id",ORDER)
                    assert 'REVIEW\tSUCCESS' in ops and 'PAY\tSUCCESS' in ops
                    assert int(sql(f"SELECT COUNT(*) FROM oms_refund_operation WHERE refund_id={ref['id']} AND action='PAY'",ORDER))==1
        checks.append(key+': actual stock, reservation, prior money-only refund and target automatic stop verified')
    errors=[];writes=[];browser_cases=[]
    with sync_playwright() as p:
        browser=p.chromium.launch(headless=True)
        context=browser.new_context(viewport={'width':1680,'height':1120})
        context.add_cookies([{'name':'Admin-Token','value':r.token,'url':BASE}])
        page=context.new_page();page.set_default_timeout(30000)
        page.on('pageerror',lambda e:errors.append(str(e)))
        page.on('request',lambda q:writes.append(q.url) if '/prod-api/' in q.url and q.method not in ['GET','HEAD','OPTIONS'] else None)
        for key,c in m['cases'].items():
            ref=c['refunds']['target'];page.goto(ref['url'],wait_until='networkidle')
            d=page.get_by_role('dialog',name=re.compile('^业务退款详情'))
            expect(d.locator('.refund-identity-main > strong')).to_have_text(ref['refund_sn'])
            expect(d.locator('.refund-fulfillment')).to_be_visible()
            page.screenshot(path=str(OUT/(key+'-refund.png')),full_page=True,animations='disabled')
            if key=='04':
                d.get_by_role('tab',name='平台处理与资金',exact=True).click()
                page.screenshot(path=str(OUT/'04-platform.png'),full_page=True,animations='disabled')
            browser_cases.append({'case':key,'refund_sn':ref['refund_sn'],'url':ref['url']})
        assert not writes,writes
        assert not errors,errors
        browser.close()
    r.report()
    grouped={str(i).zfill(2):'FAIL' for i in range(1,10)};grouped['10']='PARTIAL'
    summary={'main_cases':10,'variants':11,'fully_passed':0,'failed':9,'partial':1,'grouped_status':grouped,'retained_orders':len(m['cases'])+len(m.get('setup_attempts',[])),'retained_refunds':sum(len(c['refunds']) for c in list(m['cases'].values())+m.get('setup_attempts',[])),'actual_stock_assertions':checks,'stocks':stocks,'browser_cases':browser_cases,'browser_errors':errors,'browser_writes':writes,'automatic_intake_schedule_tested':False,'unreached_steps':['actual small-refund auto-decision','case05 simultaneous cutoff/outbound race','case06 carrier intercept','case07 automatic return creation, warehouse return dispatch, partial receipts','case08 cross-physical-warehouse branch','case09 subsequent return, gift recovery and final refund','case10 explicit cumulative money limit rule'],'case04_blocker':'SIMULATION PAY changes status but retains refund_version/modified, causing SOURCE_CONFLICT; cancellation stock effects succeeded'}
    (OUT/'verification.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n','utf-8')
    cards=[]
    for key,c in m['cases'].items():
        refs=''.join('<li><a href="'+html.escape(ref['url'])+'">'+html.escape(ref['refund_sn'])+'</a> · '+html.escape(ref['amount'])+'元'+(' · 前置已完成纯资金退款（非自动审批通过）' if ref['setup_only'] else ' · 本次目标退款')+'</li>' for ref in c['refunds'].values())
        lines=''.join('<tr><td>'+html.escape(x[0])+'</td>'+''.join('<td>'+html.escape(str(v))+'</td>' for v in x[1:])+'</tr>' for x in stocks[key]['company'])
        cards.append('<article id="case'+key+'"><h2>'+key+' '+html.escape(c['title'])+' <small>'+html.escape(c['status'])+'</small></h2><p>'+html.escape(SCENES[key])+'</p><p class="finding">实际结果：'+html.escape(c['finding'])+'</p><p>店铺：'+html.escape(c['channel_name'])+'（'+str(c['channel_id'])+'）<br>业务订单：<a href="'+html.escape(c['order_url'])+'">'+html.escape(c['order_sn'])+'</a></p><ul>'+refs+'</ul><table><thead><tr><th>SKU</th><th>当前总库存</th><th>可用</th><th>预占</th><th>已分配</th></tr></thead><tbody>'+lines+'</tbody></table><p><a href="'+key+'-refund.png">查看退款详情截图</a></p></article>')
    archive=''.join('<li>前置准备记录：<a href="'+html.escape(c['order_url'])+'">'+html.escape(c['order_sn'])+'</a>，'+html.escape(c['setup_issue'])+'</li>' for c in m.get('setup_attempts',[]))
    content='<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>发货后仅退款自动流程验收</title><style>body{font:14px/1.8 system-ui,sans-serif;color:#24374d;background:#f3f6fa;margin:0}main{max-width:1120px;margin:32px auto;padding:0 20px}header,article{background:white;border:1px solid #e1e8f0;border-radius:10px;padding:24px;margin:20px 0}h1{font-size:24px}h2{font-size:18px}a{color:#076acc;overflow-wrap:anywhere}.finding{padding:12px;background:#fff4e5}small{font-size:12px;color:#b54a28}table{width:100%;border-collapse:collapse;font-size:13px}td,th{padding:8px;border-bottom:1px solid #e5eaf1;text-align:left}td:first-child{overflow-wrap:anywhere}nav{display:flex;flex-wrap:wrap;gap:14px}.totals{font-size:18px;font-weight:600}</style><main><header><h1>发货后仅退款自动流程验收</h1><p>批次 '+m['run_id']+' · 数据保留在本地环境</p><p class="totals">10条主用例 / 11组变体：完整通过0，未通过9，部分验证1</p><p>保留12张业务订单、15张业务退款（含前置与准备记录）。所有平台操作均为SIMULATION，仓库为TEST。目标退款未人工审核、未人工付款、未人工补建退回安排。</p><p>全局Quartz原本暂停且保持不变。本次按测试店铺、退款ID定向补拉和唤醒自动流程；定时接入未验。20元累计小额免退是目标规则，现系统没有该配置；500元仅为自动付款上限。</p><p>截单用例的库存取消成功，模拟平台付款响应成功，但最终资金状态因同版本状态冲突未确认。其他失败用例停在用途和货物方案待确认。部分停止行为不能算目标规则通过。</p><nav>'+''.join('<a href="#case'+k+'">'+k+'</a>' for k in m['cases'])+'</nav><p><a href="results.json">完整结果与单据编号</a> · <a href="verification.json">库存及页面核对</a> · <a href="manifest.json">全部阶段数据</a></p></header>'+''.join(cards)+'<article><h2>保留的前置准备记录</h2><ul>'+archive+'</ul><p>用例08重分仓前的仓库单、撤回记录均保留；正式部分出库数据为两张有效发货单，分别已出1件与未出2件，属于同一实体仓下两个虚拟仓。真实物流轨迹、截单竞争及退回入账等未到达分支，不计通过。</p></article></main></html>'
    (OUT/'验收报告.html').write_text(content,'utf-8')
    print('PASS retained data verification: 11 variants, stock, 11 real refund pages, no browser writes; target auto-flow pass count remains 0')


if __name__=='__main__':main()
