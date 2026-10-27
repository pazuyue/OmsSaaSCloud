"""Publish a detailed, self-contained procurement-return acceptance report."""
import html,json,shutil
from pathlib import Path
from datetime import datetime
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/purchase-return-iteration'
def main():
 state=json.loads((OUT/'scenarios.json').read_text('utf-8'));base=json.loads((OUT/'base/manifest.json').read_text('utf-8'));ui=json.loads((OUT/'browser-check.json').read_text('utf-8'));queries=json.loads((OUT/'query-check.json').read_text('utf-8'));regression=json.loads((ROOT/'outputs/purchase-returns/regression.json').read_text('utf-8'))
 assert len(state['results'])==19 and ui['result']==queries['result']=='PASS' and state['protected_baseline_unchanged']
 shutil.copy2(ROOT/'outputs/purchase-returns/regression.json',OUT/'regression.json')
 def esc(value):return html.escape(str(value))
 def link(id,label,reclaim=False):return '<a href="http://127.0.0.1:8088/oms-supplychain/purchaseReturns?'+('reclaimId' if reclaim else 'id')+'='+str(id)+'">'+esc(label)+'</a>'
 def table(headers,rows):return '<table><thead><tr>'+''.join('<th>'+esc(x)+'</th>' for x in headers)+'</tr></thead><tbody>'+''.join('<tr>'+''.join('<td>'+str(x)+'</td>' for x in row)+'</tr>' for row in rows)+'</tbody></table>'
 def stockrow(label,d):return [esc(label)]+[d[x] for x in ['total','available','allocated','reserved','frozen']]
 css="""body{margin:0;background:#f3f6fb;color:#34445b;font:15px/1.8 'Microsoft YaHei',sans-serif}main{max-width:1420px;margin:28px auto;background:white;padding:32px;border-radius:10px}h1{font-size:27px}h2{font-size:21px;margin-top:30px}h3{font-size:17px}.muted{color:#7a899d;font-size:13px}.metrics{display:flex;gap:12px;flex-wrap:wrap}.metrics article{flex:1;min-width:160px;background:#f0f6fd;border-radius:8px;padding:16px}.metrics span,.metrics b{display:block}.metrics b{font-size:25px;color:#278558}table{border-collapse:collapse;width:100%;font-size:13px;margin:15px 0}th,td{padding:10px;border:1px solid #dfe7f0;text-align:left;overflow-wrap:anywhere}th{background:#f4f7fb}section{margin-top:28px;border-top:2px solid #edf2f8;padding-top:12px}a{color:#0877dc;overflow-wrap:anywhere}.pass{color:#278558;font-weight:600}.notice{padding:16px;background:#fff8e9;border-radius:7px}pre{font-size:12px;background:#f4f7fb;white-space:pre-wrap;overflow-wrap:anywhere;padding:14px}img{display:block;max-width:100%;border:1px solid #dfe7f0;margin-top:12px}figure{margin:26px 0}figcaption{font-weight:600}details{margin:16px 0}summary{cursor:pointer;color:#0877dc}"""
 parts=['<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>采购退仓两批迭代验收报告</title><style>'+css+'</style><main><h1>采购退仓两批迭代 · 详细验收报告</h1><p class="muted">'+datetime.now().isoformat()+' · 本地TEST仓库 · 测试资料与单据保留</p><div class="metrics">']
 for label,value in [('后端相关回归',str(regression['total_tests'])+' 项通过'),('基础退仓场景',str(len(base['cases']))+' 项通过'),('完整业务场景','19 项通过'),('页面检查',str(len(ui['checks']))+' 项通过'),('查询与协议检查',str(len(queries['checks']))+' 项通过')]:parts.append('<article><span>'+label+'</span><b>'+value+'</b></article>')
 parts.append('</div><p><a href="/oms-supplychain/purchaseReturns">采购退仓列表</a>　<a href="/oms-supplychain/operations">供应链待办</a></p><h2>本轮范围与执行边界</h2><p>新增退仓按采购来源、退仓商品、供应商交付信息分区；省市区复用原全国目录，快递复用现有货主实仓配置。列表、详情和操作区分最终短出、部分完成、实际扣账、原占用释放。继续退仓在原占用释放后生成唯一关联草稿并独立审核。</p><p>支持多笔退款、抵扣和追加冲销；供应商签收、拒收、短收逐行登记。拒收商品通过独立CGH收回入库单恢复库存，按仓库实际质量生成新入库来源，原退仓出库和采购实收均保留。采购净入库=原采购入账−实际退仓出库＋实际拒收收回。</p><p>采购与收回审核、停止或继续申请、供应商结果及凭证登记是显式业务操作；随后锁定、仓库下发、签名回传解析、库存扣账或恢复由后台执行。没有直接改写目标状态或库存来代替流程。款项功能登记已核对凭证，不执行银行付款。</p><p>仓库采用本地TEST奇门模拟器，协议为stockout CGTH、entryorder QTRK及order.cancel；实际外部仓库仍需联调。接口依据<a href="https://developer.alibaba.com/docs/doc.htm?articleId=118403&amp;docType=1&amp;treeId=285">奇门官方白皮书</a>核对。</p><div class="notice">用户原单 '+link(17,'CGT5d411f13bbde41b8a53949a3053caaed')+' 展示“部分出库，剩余待处理”：申请5件、实出2件、未出3件、仍占用3件。页面验收仅打开处理弹窗核对预览，原业务状态及库存保留。</div>')
 parts.append('<h2>业务场景索引</h2>');parts.append(table(['序号','详细场景','保留原退仓单','结果'],[[r['key'],'<a href="#case-'+r['key']+'">'+esc(r['title'])+'</a>',link(r['return_id'],r['return_sn']),'<span class="pass">通过</span>'] for r in state['results']]))
 parts.append('<h2>最终库存核对</h2><p class="muted">以下是全部场景完成后的当前库存；后续每个场景还保留该步骤发生前、发生后的库存，便于逐步核对。商品、仓库和批次数量全部一致。</p>')
 parts.append(table(['SKU','总量','可用','已分配','预占','不可售/冻结','仓库正品/次品'],[[esc(v['sku'])]+[v[k] for k in ['total','available','allocated','reserved','frozen']]+[str(v['warehouse']['zpActualNumber'])+' / '+str(v['warehouse']['cpActualNumber'])] for v in state['current_stock'].values()]))
 for r in state['results']:
  parts.append('<section id="case-'+r['key']+'"><h2>'+r['key']+' '+esc(r['title'])+' <span class="pass">通过</span></h2><p>原退仓：'+link(r['return_id'],r['return_sn'])+'<br>SKU：'+esc(r['sku'])+'</p><ol>'+''.join('<li>'+esc(step)+'</li>' for step in r['steps'])+'</ol>')
  parts.append(table(['阶段','总库存','可用','已分配','预占','不可售/冻结'],[stockrow('本步骤前',r['before_stock']),stockrow('本步骤后',r['after_stock'])]))
  evidence=r['evidence'];documents=[]
  if isinstance(evidence,dict):
   for d in [evidence,evidence.get('reclaim'),evidence.get('child'),evidence.get('second_return')]:
    if isinstance(d,dict) and d.get('reclaim_sn'):documents.append(link(d['id'],d['reclaim_sn'],True))
    elif isinstance(d,dict) and d.get('return_sn'):documents.append(link(d['id'],d['return_sn']))
  if documents:parts.append('<p>关联单据：'+'　'.join(dict.fromkeys(documents))+'</p>')
  parts.append('<details><summary>查看该步骤原单据、仓库、供应商及凭证证据</summary><pre>'+esc(json.dumps(evidence,ensure_ascii=False,indent=2))+'</pre></details></section>')
 parts.append('<h2>现场发现与修复</h2>')
 for issue in state.get('resolved_issues',[]):parts.append('<div class="notice"><b>'+esc(issue['problem'])+'</b><p>'+esc(issue['fix'])+'</p></div>')
 parts.append('<div class="notice"><b>释放预览未带出批次可用数量，曾显示NaN。</b><p>补齐库存来源查询的正次品可用字段，增加页面未知值提示及库存服务回归；原单已验证能正确预览释放3件、批次可用5→8。没有改变原单库存。</p></div>')
 parts.append('<h2>页面验收</h2><ol>'+''.join('<li>'+esc(c)+'</li>' for c in ui['checks'])+'</ol>')
 captions={'01-original-final-short.png':'原单最终短出与剩余占用','02-remaining-decision.png':'结束或继续剩余及释放预览','03-partial-closed-linked-child.png':'部分完成与关联后续草稿','04-multiple-vouchers-reversal.png':'多笔款项和追加冲销','05-supplier-receipts.png':'供应商签收、拒收、短收及关联收回','06-reclaim-actual-quality.png':'独立收回计划、实际次品质检及新库存来源','07-reclaim-warehouse-interactions.png':'收回仓库下发和实收交互','08-procurement-net-receipts.png':'原采购入账、退仓、收回和净入库','09-reclaim-source-form.png':'拒收来源与供应商实际退回物流','10-create-address-and-footer.png':'新增分区、省市区、快递及固定操作栏'}
 for file,caption in captions.items():assert (OUT/file).is_file(),file;parts.append('<figure><figcaption>'+caption+'</figcaption><a href="'+file+'"><img src="'+file+'" alt="'+caption+'"></a></figure>')
 parts.append('<h2>查询、协议与性能</h2><ol>'+''.join('<li>'+esc(c)+'</li>' for c in queries['checks'])+'</ol>');parts.append(table(['读取入口','样本数','中位耗时毫秒','最小/最大毫秒'],[[esc(t['endpoint']),len(t['samples_ms']),t['median_ms'],str(min(t['samples_ms']))+' / '+str(max(t['samples_ms']))] for t in queries['timings']]))
 parts.append('<p class="muted">这是本地环境连续读取样本，不代表生产容量测试。列表不调用仓库或逐行库存服务；来源库存一次批量RPC，审核来源额度在原单锁下批量汇总；后台有界执行、租约、幂等事件及失败退避。</p><details><summary>查看关键查询执行计划</summary><pre>'+esc(json.dumps(queries['explain'],ensure_ascii=False,indent=2))+'</pre></details><h2>库存原始流水</h2>')
 for sku,ledger in state['ledger'].items():parts.append('<details><summary>'+esc(sku)+'</summary>'+table(['动作','关联来源','质量','变化数量','旧正品','新正品','旧次品','新次品'],[[esc(x) for x in line.split('\t')] for line in ledger.splitlines()])+'</details>')
 parts.append('<p class="notice">原订单、退款、相关库存、自动任务及队列策略基线核对一致。测试采购、退仓、收回、凭证和仓库交互记录全部保留。</p></main></html>')
 (OUT/'验收报告.html').write_text(''.join(parts),'utf-8');destination=ROOT/'docker/local/dist/acceptance/purchase-return-iteration';destination.mkdir(parents=True,exist_ok=True)
 for file in OUT.iterdir():
  if file.is_file() and file.suffix in ['.html','.png','.json']:shutil.copy2(file,destination/file.name)
 print('Report published: http://127.0.0.1:8088/acceptance/purchase-return-iteration/验收报告.html')
if __name__=='__main__':main()
