-- Configuration-only protection, including in-flight enables. No order or stock backfill.
CREATE TABLE IF NOT EXISTS goods_gift_strategy_ref (
 company_code VARCHAR(50) NOT NULL, group_id BIGINT NOT NULL,
 request_token CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 sku_sn VARCHAR(128) NOT NULL, group_name VARCHAR(100) NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(company_code,group_id,request_token,sku_sn),
 KEY gift_product_refs(company_code,sku_sn,group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
