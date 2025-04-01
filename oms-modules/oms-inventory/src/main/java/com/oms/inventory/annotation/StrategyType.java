package com.oms.inventory.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 策略类型注解，用于标识具体的策略实现类
 */
@Target(ElementType.TYPE) // 作用于类上
@Retention(RetentionPolicy.RUNTIME) // 运行时可通过反射获取
public @interface StrategyType {
    String value(); // 策略类型的唯一标识符
}
