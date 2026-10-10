CREATE TABLE IF NOT EXISTS owner_warehouse (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '货主与实仓关联主键ID，供虚仓owner_warehouse_id引用',
 company_code VARCHAR(255) NOT NULL COMMENT '所属公司编码，货主、实仓及引用虚仓必须属于同一公司',
 owner_id INT NOT NULL COMMENT '货主ID，关联owner_info.id',
 real_store_id INT NOT NULL COMMENT '实体仓库ID，关联wms_real_store_info.id',
 status TINYINT NOT NULL DEFAULT 2 COMMENT '关联启用状态：1停用，2启用；业务可用还需货主和实仓启用',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '关联创建时间',
 modify_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '关联最近修改时间',
 PRIMARY KEY(id), UNIQUE KEY uk_owner_warehouse(owner_id,real_store_id),
 KEY idx_relation_company(company_code(64))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='货主与实体仓库多对多关联表，维护关联状态，作为虚仓固定归属依据';
