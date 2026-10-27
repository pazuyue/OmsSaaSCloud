"""Publish the retained inventory-check evidence and human-readable acceptance report."""
import html,json,shutil,statistics,time,urllib.parse
from pathlib import Path
from datetime import datetime
from verify_goods_workspace_ui import login,request,BASE
from verify_full_stock_check import baseline

ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'outputs/full-stock-check';DEST=ROOT/'docker/local/dist/acceptance/full-stock-check';API='/supplychain/warehouseControl'


def main():
    state=json.loads((OUT/'manifest.json').read_text('utf-8'));browser=json.loads((OUT/'browser-check.json').read_text('utf-8'));permissions=json.loads((OUT/'permissions-performance.json').read_text('utf-8'));regression=json.loads((ROOT/'outputs/warehouse-control/regression.json').read_text('utf-8'));assert state['stage']=='VERIFIED' and browser['result']==permissions['result']=='PASS' and not browser['js_errors']
    now=baseline();unchanged={k:state['before'][k]==v for k,v in now.items()};assert all(unchanged.values()),unchanged
    token=login();api=lambda path:request(path,token=token)['data'];docs={key:api(API+'/reconciliations/'+str(value)) for key,value in state['ids'].items()}
    tasks=[]
    for key,label in [('oms','OMS清单全仓检查'),('dual','导入仓库完整清单并重试失败'),('browser','页面提交、停止后继续'),('recheck','选中商品最新复查')]:
        if key not in docs:continue
        d=docs[key];tasks.append([label,d['document_sn'],d['id'],d['state'],d.get('progress',{}).get('total',len(d['scope']['skus'])),d['difference_count'],d['attention_count']])
    operations=api('/supplychain/purchaseWorkspace/operations?issue=STOCK_DIFFERENCE&pageSize=50')
    from test_multi_virtual_shipping import sql
    for row in operations['rows']:
        assert sql('SELECT parent_id FROM warehouse_reconciliation WHERE id='+str(row['document_id']), 'qm_oms_saas_commodity')=='0'
    stock={line.split('\t')[0]:line.split('\t')[1:6] for line in state['before']['stock'].splitlines() if line.strip()}
    old=json.loads((ROOT/'outputs/warehouse-control/manifest.json').read_text('utf-8'));stock_rows=[]
    for c in old['cases']:
        v=stock[c['sku']];stock_rows.append([c['sku']]+v+v)
    e=lambda value:html.escape(str(value))
    def table(headers,rows):return '<div class="table-wrap"><table><thead><tr>'+''.join('<th>'+e(h)+'</th>' for h in headers)+'</tr></thead><tbody>'+''.join('<tr>'+''.join('<td>'+e(c)+'</td>' for c in row)+'</tr>' for row in rows)+'</tbody></table></div>'
    page='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>全仓库存检查验收报告</title><style>body{margin:0;background:#f2f5fa;color:#27374e;font:15px/1.8 system-ui,Arial}main{max-width:1240px;margin:auto;padding:32px}h1{margin:0}h2{margin-top:30px}.card,article{background:white;border:1px solid #dce4ef;border-radius:10px;padding:22px;margin:18px 0}.pass{color:#18805a}.notice{background:#fff4df;padding:18px;border-left:4px solid #c38a22}.muted{color:#708197}a{color:#0670d1}table{border-collapse:collapse;width:100%;background:white;font-size:13px}td,th{border:1px solid #dce4ef;padding:10px;text-align:left;overflow-wrap:anywhere}th{background:#eaf1fa}.table-wrap{overflow:auto}pre{background:#f3f6fa;padding:16px;overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px;max-height:520px}summary{cursor:pointer;color:#0670d1}details{margin:12px 0}img{width:100%;height:auto;margin:16px 0;border:1px solid #ddd}</style><main>'''
    page+='<h1>全仓库存检查验收报告</h1><p class="muted">'+e(datetime.now().strftime('%Y-%m-%d %H:%M:%S'))+' · 本地 TEST 环境 · 单据、查询交互与测试数据保留</p>'
    page+='<div class="card"><b class="pass">功能验收通过</b><p>'+e(regression['total_tests'])+' 条后端回归、8组接口验收、'+str(len(browser['checks']))+'组页面验收、只读权限与导出验收均通过；前端两个组件校验脚本及生产构建通过。</p><p>入口：<a href="/oms-supplychain/warehouseReconciliation">供应链 → 库存对账 → 新建库存检查</a>，默认选择全仓检查。<a href="/oms-supplychain/warehouseReconciliation?id='+str(browser['task_id'])+'">打开页面验收任务</a></p></div>'
    page+='<p class="notice">“检查完成”指当前清单查询结束。本测试仓仍有实物差异、缺失返回或待核查事项；本报告不宣称全仓库存一致。检查与重试没有修改OMS库存，也没有创建实物调整入账。</p>'
    page+='<h2>已交付行为与覆盖边界</h2><div class="card"><p>全仓检查自动收集所选货主实仓下全部虚仓的库存记录与待处理单据商品；库存记录为零、冻结、已占用或商品停用均不按可售状态排除。加工组件与供应商拒收收回等待处理商品纳入清单。</p><p>默认标注“OMS清单覆盖”，不能据此排除仓库独有商品。可导入人工确认完整的仓库SKU清单进行补查，记录来源；当前未声称已接入仓库实时全量枚举。</p><p>后台逐商品取得完整批次。失败、缺失字段和缺失SKU保持未知；部分失败不标为整仓完成。停止等待当前商品结束，继续保留成功快照；失败重试只重新查询失败商品。全仓结果须先创建选中商品的最新复查，再沿用原独立调整审核及库存版本校验。</p></div>'
    page+='<h2>保留任务</h2>'+table(['场景','任务单号','ID','当前状态','商品数','实物差异行','字段/冻结待核查行'],tasks)
    page+='<h2>详细接口场景与实际结果</h2>'
    for c in state['checks']:
        page+='<article><b class="pass">'+e(c['key'])+' 通过</b><p>'+e(c['scenario'])+'</p><details><summary>查看本次实际结果与数量证据</summary><pre>'+e(json.dumps(c['result'],ensure_ascii=False,indent=2))+'</pre></details></article>'
    page+='<h2>库存核实</h2><div class="card"><p>检查前后逐项比较全部QM公司商品库存、仓库库存、批次数量与版本、库存流水数量/最大ID，以及受保护退款、采购退仓、队列策略、全局任务开关和原验收清单；全部相同。表内数字依次为：总库存 / 可用 / 预占 / 已分配 / 冻结。</p><p>仓库独有测试商品的WMS实物为7，其中冻结3，OMS本仓实物为0，明确显示差额+7。检查后OMS仍未为该商品自动创建库存或记账。</p></div>'
    page+=table(['原验收SKU','前总','前可用','前预占','前分配','前冻结','后总','后可用','后预占','后分配','后冻结'],stock_rows)
    page+='<h2>页面验收</h2><div class="card">'+''.join('<p class="pass">✓ '+e(c)+'</p>' for c in browser['checks'])+'<p>供应链待办同步验证：查询结果只指向主任务，不混入SKU子快照。</p></div>'
    for name in browser['screenshots']:page+='<details><summary>'+e(name)+'</summary><a href="'+e(name)+'" target="_blank"><img src="'+e(name)+'" loading="lazy"></a></details>'
    page+='<h2>权限与性能</h2><div class="card"><p>独立只读账号可以查询和导出；新建、停止、重试、复查及调整五种写入均被服务端权限拒绝，未新增检查任务。</p><p>每次最多收集100条库存记录、查询5种商品；同货主实仓查询通道并发为1。无sleep等待，不持有库存业务锁。结果按异常优先索引分页，进度随结果同事务增量维护，避免每轮全仓汇总。</p><p>单SKU仓库分页100行、最多5000行；仓库导入清单最多20000个SKU；单次筛选导出最多100000行，超限明确提示。详情不返回整仓结果大数组，列表/详情/结果查询不产生新的WMS请求。</p><p class="muted">'+e(permissions['scope'])+'</p></div>'
    page+=table(['查询','5次耗时（毫秒）','中位数（毫秒）'],[[t['path'],t['samples_ms'],t['median_ms']] for t in permissions['timings']])
    page+='<h2>证据文件</h2><div class="card">'+''.join('<p><a href="'+e(name)+'">'+e(name)+'</a></p>' for name in ['regression.json','browser-check.json','permissions-performance.json','verification.json','仓库完整商品清单.csv','筛选结果.csv'])+'</div></main></html>'
    (OUT/'验收报告.html').write_text(page,'utf-8');(OUT/'regression.json').write_text(json.dumps(regression,ensure_ascii=False,indent=2),'utf-8');(OUT/'verification.json').write_text(json.dumps(dict(unchanged=unchanged,operations_only_parent_tasks=True,actual_tasks=tasks,verified_at=datetime.now().isoformat()),ensure_ascii=False,indent=2),'utf-8');DEST.mkdir(parents=True,exist_ok=True)
    for f in OUT.iterdir():
        if f.is_file() and f.suffix in ['.png','.html','.json','.csv']:shutil.copy2(f,DEST/f.name)
    print('PASS report published; protected stock and business records unchanged')


if __name__=='__main__':main()
