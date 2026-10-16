package com.flexiatom.floatingassistant.tool;

import com.flexiatom.floatingassistant.service.SphereAccessibilityService;

import java.util.List;

/**
 * 容器工具（#68 径向树）：选中不触发动作，而是向外**再加画一圈同心环**展开其 {@link #children()} 子项。
 * {@link #run} 对容器态不会被调用（服务据 {@link #isContainer()} 转向展开），留空实现仅满足接口。
 */
public final class ContainerTool implements Tool {

    private final String id;
    private final String label;
    private final List<Tool> children;

    public ContainerTool(String id, String label, List<Tool> children) {
        this.id = id;
        this.label = label;
        this.children = children;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public void run(SphereAccessibilityService svc) {
        // 容器不触发动作；选中由菜单转为展开 children。
    }

    @Override
    public List<Tool> children() {
        return children;
    }
}
