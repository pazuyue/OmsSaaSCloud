"""Render the retained acceptance evidence and publish only the review report and screenshots locally."""
import html
import json
import shutil
from pathlib import Path
from verify_return_refund_auto import OUT,ROOT

def main():
    data=json.loads((OUT/'results.json').read_text('utf-8'));manifest=json.loads((OUT/'manifest.json').read_text('utf-8'));ui=json.loads((OUT/'ui-verification.json').read_text('utf-8'));tests=json.loads((OUT/'backend-tests.json').read_text('utf-8'))
    assert len(data['rows'])==10 and all(r['status']=='PASS' for r in data['rows'])
    assert not ui['write_requests'] and not ui['page_errors'] and all(data['protection'].values())
    assert json.loads((OUT/'07-pool-verification.json').read_text('utf-8'))['unchanged']
    esc=lambda x:html.escape(str(x),quote=True)
    link=lambda label,url:'<a href="'+esc(url)+'">'+esc(label)+'</a>'
    table=[];cards=[]
    for row in data['rows']:
        key=row['case'];c=manifest['cases'][key];refunds='<br>'.join(link(('前笔差价20元 · ' if role=='prior' else '')+ref['refund_sn'],ref['url']) for role,ref in row['refunds'].items())
        verdict='预期超额保护通过' if key=='09' else '自动闭环通过'
        table.append('<tr><td>'+key+'</td><td>'+esc(row['scenario'])+'</td><td class="'+('protect' if key=='09' else 'pass')+'">'+verdict+'</td><td>'+link(row['order_sn'],row['order_url'])+'</td><td>'+refunds+'</td><td>¥'+format(row['paid'],'.2f')+'</td></tr>')
        before={r[0]:r for r in c['stock_before_refunds']['company']};channel={r[0]:r for r in row['stock']['channel']};before_channel={r[0]:r for r in c['stock_before_refunds']['channel']}
        stocks=''.join('<tr><td>'+esc(r[0])+'</td><td>'+before[r[0]][1]+' → '+r[1]+'</td><td>'+before[r[0]][2]+' → '+r[2]+'</td><td>'+r[3]+' / '+r[4]+'</td><td>'+before_channel[r[0]][4]+' → '+channel[r[0]][4]+'</td><td>'+before_channel[r[0]][1]+' → '+channel[r[0]][1]+'</td></tr>' for r in row['stock']['company'])
        states=''.join('<li>'+esc(s['id'])+'：用途 '+esc({'CANCEL':'取消购买','DIFFERENCE':'差价','UNKNOWN':'待确认'}.get(s['purpose'],s['purpose']))+'；审核 '+esc(s['review_by'] or '尚未审核')+'；业务 '+esc({'COMPLETED':'处理完成','NEEDS_REVIEW':'待核对'}.get(s['state'],s['state']))+'；资金 '+esc({'SUCCESS':'平台退款成功','NONE':'本退款未付款'}.get(s['funds'],s['funds']))+'</li>' for s in row['states'])
        extra=''
        if key=='05':extra='<p>'+esc(row['note'])+'</p><p>原运单：<code>'+esc(c['initial_return_waybill'])+'</code>；补寄资料：<code>'+esc(c['supplement_waybill'])+'</code></p>'
        if key=='06':
            v=row['merge_dispatch_evidence'].split('\t');extra='<p>最后加入 '+esc(v[2])+'；到期 '+esc(v[3])+'；实际建单 '+esc(v[4])+'。等待窗口 '+v[5]+' 秒，到期后 '+v[6]+' 秒建单；4张退款、1张共同入库单。</p><p class="muted">前置平台处理耗时影响了加入间隔；本轮按实际最后加入时间验证120秒等待，没有声称精确复现0/30/60/100秒的加入节奏。</p>'
        if key=='07':extra='<p>套装退款只关联赠品套装E/F，福袋退款只关联赠品福袋G/H。八个实际SKU各恢复100，虚拟父项不重复增加实物库存。福袋池仍保留已消费事实，未自动生成新的可售福袋。</p>'+link('福袋池前后核对','07-pool-verification.json')
        if key in ['08','09']:extra='<p>第一笔20元从待处理状态开始，采用事先登记的商家差价授权，审核及付款均由系统执行。授权登记是明确的业务依据，不代表系统凭金额或关键词识别差价。</p>'
        if key=='09':extra+='<p class="protect">第二笔100元超过剩余80元，自动转人工，未同意该退货申请、未付款。此处通过指金额保护符合预期，原轮未覆盖人工后续处理。</p>'+link('超额转人工的页面修正与申请改80后的补充验收','../return-refund-amount-resolution/验收报告.html')
        if key=='10':extra='<p>相同签名仓库回传并发发送3次，库存只增加1件。模拟平台付款已成功但响应丢失，原请求保持UNKNOWN记录，由查询确认最终成功；OMS与渠道各只有1笔付款请求。</p>'
        screenshots=' '.join(link(p.name,p.name) for p in sorted(OUT.glob(key+'-*.png')))
        cards.append('<article id="case'+key+'"><h2>'+key+' '+esc(row['scenario'])+'</h2><p>'+refunds+'</p>'+extra+'<ul>'+states+'</ul><p>人工退款审核事件：'+str(row['manual_review_events'])+'；平台动作均来自自动售后流程。</p><table><tr><th>实际SKU</th><th>公司总库存</th><th>公司可用</th><th>预占 / 分配</th><th>渠道配额</th><th>渠道可售</th></tr>'+stocks+'</table><p>'+screenshots+'</p><details><summary>付款幂等证据</summary><pre>'+esc(json.dumps(row['payment_counts'],ensure_ascii=False,indent=2))+'</pre></details></article>')
    body='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>退货退款自动流程 · 复测验收</title><style>body{font:15px/1.8 system-ui,"Microsoft YaHei",sans-serif;background:#f4f7fb;color:#273b53;margin:0}main{max-width:1440px;margin:28px auto;padding:0 20px}header,article{background:white;border:1px solid #e0e7f0;border-radius:10px;padding:24px;margin-bottom:20px}h1{font-size:25px}h2{font-size:19px}a{color:#1677d2;overflow-wrap:anywhere}table{width:100%;border-collapse:collapse;font-size:13px}td,th{border-bottom:1px solid #e4eaf2;text-align:left;padding:10px;overflow-wrap:anywhere}th{background:#f5f8fc}.pass{color:#258464;font-weight:600}.protect{color:#a36c18;font-weight:600}.muted{font-size:13px;color:#75869c}pre{white-space:pre-wrap;font-size:12px}code{overflow-wrap:anywhere}.table-scroll{overflow:auto}@media(max-width:800px){main{padding:0 10px}header,article{padding:15px}table{min-width:680px}}</style><main>'''
    body+='<header><h1>退货退款自动流程：10条用例复测通过</h1><p class="pass">9条自动完成货物与资金闭环；1条累计超额保护通过。</p><p>批次 '+esc(data['run_id'])+'。最终报告使用10张验收订单、16张退款（含差价前置）；另保留2张首轮问题订单、3张退款，未改写原审核或执行记录。</p><p>目标退款人工审核、人工建单、人工下发、人工付款、手工推进流程均为0。第08/09条事先登记商家差价业务授权，采用用途及实际审核、付款来源分别留证。</p><p>'+str(tests['order_tests'])+'条订单/售后回归、'+str(tests['channel_tests'])+'条渠道接口回归及浏览器验收通过；浏览器无写请求、无脚本错误。已有店铺设置、受保护库存、队列开关及调度配置保持原样。</p><p class="muted">本地SIMULATION平台、TEST仓库。以定向退款号输入模拟来源事件，由真实消息消费者继续处理；未验全局定时接入及真实平台资金。</p><div class="table-scroll"><table><tr><th>用例</th><th>场景</th><th>结论</th><th>业务订单</th><th>退款单</th><th>累计已退</th></tr>'+''.join(table)+'</table></div></header>'
    body+='<article><h2>本轮排查与修复</h2><ol><li>补齐退货退款本地方案自动审核；审核时原子预占实际发货来源，检查累计退款预算与明确数量。</li><li>增加指定退款号、金额和业务凭证的差价授权，保留商家授权与自动审核、付款的来源区别。</li><li>修复多笔退款同时追回不同赠品时的错误关联；按原独立商品策略限定归属，无法唯一归属的共同活动保留核对保护。</li><li>修复运单变化导致原THK补收被阻断的问题；原真实回传重新校验分配，待核对计划继续同步原实收，原运单不覆盖。</li><li>纠正库存断言：校验渠道配额账面值不增加；福袋组件可售可以随实物库存恢复，不将其误判为配额扩张。</li><li>修复已完成补收的进度误报：原THK已全量实收入库时，运单变更只保留为物流追溯，不再显示“实收待核对”；真实待分配、待入库或其他异常仍保留待办。</li></ol><p>首轮问题现场及原始回传保留于输出目录的initial-run和before-fix文件。原始数据：'+link('最终结果JSON','results.json')+' · '+link('浏览器核对','ui-verification.json')+' · '+link('后端回归','backend-tests.json')+'</p></article>'
    body+=''.join('<div class="table-scroll">'+c+'</div>' for c in cards)+'</main></html>'
    (OUT/'验收报告.html').write_text(body,'utf-8')
    dest=ROOT/'docker/local/dist/acceptance/return-refund-auto';dest.mkdir(parents=True,exist_ok=True)
    for file in [OUT/'验收报告.html',OUT/'results.json',OUT/'ui-verification.json',OUT/'backend-tests.json',OUT/'07-pool-verification.json',*OUT.glob('*.png')]:
        temporary=dest/(file.name+'.next');shutil.copy2(file,temporary);temporary.replace(dest/file.name)
    print('Published local report: /acceptance/return-refund-auto/验收报告.html')

if __name__=='__main__':main()
