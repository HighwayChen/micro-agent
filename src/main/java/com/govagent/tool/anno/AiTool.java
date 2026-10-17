package com.govagent.tool.anno;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明一个可被 LLM 调用的 tool。标注在 ToolBinding 实现类的公有方法上。
 * <p>
 * permission=READ 直接执行；permission=WRITE 不直接执行，而是生成待确认预案，
 * 用户在聊天页确认后才真正执行（人工确认状态机）。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AiTool {

    /** tool 名称（全局唯一，LLM function name） */
    String name();

    /** 给 LLM 看的功能描述 */
    String description();

    ToolPermission permission() default ToolPermission.READ;

    /** 写操作的风险说明，确认卡片上展示 */
    String riskDesc() default "";

    /** 写操作摘要模板，占位符 {paramName} 取自入参，用于确认卡片 */
    String summaryTemplate() default "";

    enum ToolPermission { READ, WRITE }
}
