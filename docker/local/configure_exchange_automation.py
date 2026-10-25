"""New exchange automation tables only; no old-shop activation or document backfill."""
from pathlib import Path
from local_db import execute

def install_order(db):
    execute((Path(__file__).parent/'migrations/20261024_exchange_flow.sql').read_text('utf-8'),db)

def install_supplychain(db):
    from configure_order import column
    execute((Path(__file__).parent/'migrations/20261024_shared_after_sale_returns.sql').read_text('utf-8'),db)
    for table in ['wms_exchange_return','wms_refund_return']:
        column(db,table,'shared_member_id',"BIGINT NULL COMMENT '公共售后退回成员ID；为空时沿原流程，原库存来源不变'")
    for name,comment in [('accepted_quantity','各来源已分配实收汇总，仅展示不作为库存凭据'),('posted_quantity','各来源已入库汇总，原来源入账后更新'),('pending_receipt_count','待逐行确认归属的回传数'),('pending_quantity','待确认归属的回传数量，不计入可用库存')]:
        column(db,'wms_shared_return_document',name,"BIGINT NOT NULL DEFAULT 0 COMMENT '"+comment+"'")
    column(db,'wms_shared_return_receipt','quantity',"BIGINT NOT NULL DEFAULT 0 COMMENT '本条仓库原消息总数，归属未确认前不入库'")
