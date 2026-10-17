package com.govagent.tool.anno;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * tool 方法参数描述，是生成 OpenAI function JSON Schema 的依据。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface AiToolParam {

    /** 参数名（LLM 传参时的 key） */
    String name();

    /** 参数含义描述 */
    String description() default "";

    boolean required() default true;

    /** 可选值枚举（生成 schema 的 enum） */
    String[] enumValues() default {};
}
