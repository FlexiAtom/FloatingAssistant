package com.flexiatom.floatingassistant.tool;

import com.flexiatom.floatingassistant.service.SphereAccessibilityService;

/**
 * 悬浮球扇形菜单里的一个工具（提案 §10 M0：注册即出现在菜单）。
 * 注册顺序即扇形自上而下的排布顺序。选中后 {@link #run} 在服务上下文里执行。
 */
public interface Tool {

    /** 稳定标识（供日志/将来持久化排序用）。 */
    String id();

    /** 菜单 chip 上显示的文字。 */
    String label();

    /** 用户选中该工具时执行。 */
    void run(SphereAccessibilityService svc);
}
