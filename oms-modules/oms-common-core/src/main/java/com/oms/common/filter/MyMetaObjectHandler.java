package com.oms.common.filter;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Date;

@Slf4j
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        //判断添加/更新的时候是否给他赋值
        Object modifyTime1 = getFieldValByName("modifyTime", metaObject);
        log.debug("modifyTime1:"+modifyTime1);
        this.strictUpdateFill(metaObject, "modifyTime", Date.class, new Date());
    }

}
