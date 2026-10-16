package com.flexiatom.floatingassistant.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 工具注册表（提案 §10 M0）：持有有序工具列表，注册顺序 = 扇形菜单自上而下的顺序。
 * 只在服务 {@code onServiceConnected} 里一次性构建，之后只读，故无需加锁。
 */
public final class ToolRegistry {

    private final List<Tool> tools = new ArrayList<>();

    /** 追加一个工具到菜单末尾（扇形自上而下顺延）。 */
    public void register(Tool tool) {
        tools.add(tool);
    }

    /** 只读视图，供菜单渲染与选中派发。 */
    public List<Tool> tools() {
        return Collections.unmodifiableList(tools);
    }

    public int size() {
        return tools.size();
    }
}
