"""Publish the supplementary local acceptance without changing the original ten-case evidence."""
import html,json,shutil
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'outputs/return-refund-amount-resolution'

def main():
    x=json.loads((OUT/'results.json').read_text('utf-8'));ui=json.loads((OUT/'ui-verification.json').read_text('utf-8'))
    assert x['status']==ui['status']=='PASS' and x['original_122_unchanged'] and not ui['write_requests'] and not ui['page_errors']
    e=lambda v:html.escape(str(v),quote=True)
    a=lambda title,url:'<a href="'+e(url)+'">'+e(title)+'</a>'
    stock=''.join('<tr><td>'+e(r[0])+'</td><td>99 → '+e(r[1])+'</td><td>'+e(r[2])+'</td><td>'+e(r[3])+' / '+e(r[4])+'</td></tr>' for r in x['stock_after']['company'])
    refs=''.join('<li>'+a(('前笔差价20元：' if role=='prior' else '修改申请后退货退款80元：')+ref['refund_sn'],ref['url'])+'</li>' for role,ref in x['refunds'].items())
    shots=''.join('<p>'+a(p.name,p.name)+'</p>' for p in sorted(OUT.glob('*.png')))
    body='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>超额转人工处理 · 补充验收</title><style>body{font:15px/1.8 system-ui,"Microsoft YaHei",sans-serif;color:#2e4259;background:#f4f7fb}main{max-width:1120px;margin:30px auto;padding:26px;background:white;border:1px solid #e1e8ef;border-radius:8px}h1{font-size:24px}h2{font-size:18px}.pass{color:#258464}.notice{padding:16px;background:#fff8eb;border-radius:6px}a{color:#1677d2;overflow-wrap:anywhere}table{width:100%;border-collapse:collapse}td,th{padding:12px;border-bottom:1px solid #e4eaf1;text-align:left}th{background:#f5f8fc}.hint{color:#748399;font-size:13px}</style><main><h1>超额转人工处理：补充验收通过</h1>'''
    body+='<p class="notice">原验收第09条只验证超额保护。人工审核同样遵守累计额度：原实付100元，已退20元，当前最多再退80元；审核凭证不能将本次平台申请100元改成80元。</p>'
    body+='<h2>本次修正</h2><ul><li>详情主待办改为“处理金额冲突”，提前展示申请、已退、占用、可退上限及超出金额。</li><li>审核弹窗展示可操作的处理指引，停用无法提交的审核表单；保留协商或拒绝的独立平台处理入口。</li><li>超额时停用同意退货、退款审核、付款及验证码入口；协商和拒绝不要求先放行超额审核。平台权限、可用操作及凭证继续由服务端检查。</li></ul>'
    body+='<h2>新单完整续跑</h2><ol><li>差价授权20元，系统自动审核、自动退款20元。</li><li>申请退货退款100元，正确转人工，无退货安排或付款。</li><li>模拟买家协商将平台本次申请修改为80元，以新来源版本同步。</li><li>系统自动审核80元方案、同意退货；买家寄回商品和赠品，仓库合包实收入库。</li><li>系统自动退款80元，累计100元；两笔各一笔付款，库存不重复增加。</li></ol><p class="pass">退款人工审核、人工建单、人工下发、人工付款、手工重试均为0。人工介入体现在商家与买家协商修改平台申请，不是覆盖退款额度。</p>'
    body+='<p>'+a(x['order_sn'],x['order_url'])+'</p><ul>'+refs+'</ul><table><tr><th>实际SKU</th><th>公司总库存</th><th>公司可用库存</th><th>预占 / 分配</th></tr>'+stock+'</table>'
    body+='<p>'+a('原超额单 TK7060be00492d406a91a6941da82e74b4','/oms-order/businessRefunds?id=122')+' 保持申请100元、待审核，未修改其资金与审核状态。</p><h2>页面与证据</h2>'+shots+'<p>'+a('新单结果与库存','results.json')+' · '+a('页面核对（无写请求）','ui-verification.json')+'</p><p class="hint">本地SIMULATION平台、TEST仓库。模拟买家修改来源事件；不代表已验证真实平台协商、拒绝或真实付款。平台协商/拒绝入口已验证可导航，未在原单提交任何平台操作。</p></main></html>'
    (OUT/'验收报告.html').write_text(body,encoding='utf-8');dest=ROOT/'docker/local/dist/acceptance/return-refund-amount-resolution';dest.mkdir(parents=True,exist_ok=True)
    for p in [OUT/'验收报告.html',OUT/'results.json',OUT/'ui-verification.json',*OUT.glob('*.png')]:shutil.copy2(p,dest/p.name)
    print('Published /acceptance/return-refund-amount-resolution/验收报告.html')

if __name__=='__main__':main()
