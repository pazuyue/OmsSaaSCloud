"""Add full-stock-check evidence fields and indexes. Never backfill stock or business records."""
from pathlib import Path
from local_db import execute


def install(warehouse, inventory):
    execute((Path(__file__).parent/'migrations/20261027_full_stock_check.sql').read_text('utf-8'),warehouse)
    fields={
        'discovered_count':"BIGINT NOT NULL DEFAULT 0 COMMENT '已生成的去重商品数量，按批次增量维护'",
        'successful_count':"BIGINT NOT NULL DEFAULT 0 COMMENT '已投影成功商品数，与结果落行同事务递增'",
        'failed_count':"BIGINT NOT NULL DEFAULT 0 COMMENT '已投影失败商品数，重试仅清除此部分'",
        'result_count':"BIGINT NOT NULL DEFAULT 0 COMMENT '分页结果行数，与子快照同事务维护，避免反复全仓汇总'",
        'scope_mode':"VARCHAR(16) NOT NULL DEFAULT 'SELECTED' COMMENT 'FULL全仓父任务、ITEM单SKU子快照、SELECTED指定商品检查'",
        'coverage':"VARCHAR(24) NOT NULL DEFAULT 'OMS_LIST' COMMENT 'OMS_LIST本仓OMS商品范围；OMS_WMS_LIST另含确认完整的仓库导出商品清单'",
        'parent_id':"BIGINT NOT NULL DEFAULT 0 COMMENT '全仓父任务；0表示独立检查，不在列表混入子任务'",
        'item_sku':"VARCHAR(128) COLLATE utf8mb4_bin NULL COMMENT '子任务唯一SKU，父任务为空'",
        'source_check_id':"BIGINT NOT NULL DEFAULT 0 COMMENT '异常复查来源，复查形成独立新快照'",
        'progress_json':"MEDIUMTEXT NULL COMMENT '生成游标、完成数量、失败数量及检查时间窗口'",
        'result_saved':"TINYINT NOT NULL DEFAULT 0 COMMENT '子快照是否已幂等投影到分页结果'",
        'result_rows':"INT NOT NULL DEFAULT 0 COMMENT '该商品已投影的结果行数'",
        'stop_requested':"TINYINT NOT NULL DEFAULT 0 COMMENT '停止后不再开始新商品；当前商品结束后停止'"
    }
    for key,definition in fields.items():
        if not execute("SHOW COLUMNS FROM warehouse_reconciliation LIKE '"+key+"'",warehouse).strip():execute('ALTER TABLE warehouse_reconciliation ADD COLUMN '+key+' '+definition,warehouse)
    indexes={'parent_page':"KEY parent_page(company_code,parent_id,create_time,id) COMMENT '主任务日期分页，不扫描单SKU子快照'",'parent_sku':"UNIQUE KEY parent_sku(company_code,parent_id,item_sku) COMMENT '同父任务SKU唯一，恢复时不重复生成'",'parent_work':"KEY parent_work(company_code,parent_id,state,id) COMMENT '分页领取未完成商品及汇总进度'"}
    for key,definition in indexes.items():
        if execute("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='warehouse_reconciliation' AND index_name='"+key+"'",warehouse).strip()=='0':execute('ALTER TABLE warehouse_reconciliation ADD '+definition,warehouse)
    if not execute("SHOW COLUMNS FROM warehouse_reconciliation_result LIKE 'sort_order'",warehouse).strip():
        execute("ALTER TABLE warehouse_reconciliation_result ADD sort_order TINYINT GENERATED ALWAYS AS (IF(status='MATCHED',1,0)) STORED COMMENT '异常排在一致结果之前，使用索引分页', ADD KEY result_order(company_code,parent_id,sort_order,id) COMMENT '异常优先分页，不排序整仓JSON'",warehouse)
    if execute("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='wms_inventory' AND index_name='check_discovery'",inventory).strip()=='0':execute("ALTER TABLE wms_inventory ADD KEY check_discovery(company_code,store_code,id) COMMENT '全仓检查只读游标发现商品，避免OFFSET和全表加载'",inventory)


if __name__=='__main__':
    install('qm_oms_saas_commodity','qm_oms_saas_inventory')
    print('Full-stock-check schema ready; no business or stock records changed')
