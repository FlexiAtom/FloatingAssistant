package com.flexiatom.floatingassistant.tool;

import com.flexiatom.floatingassistant.service.SphereAccessibilityService;

import java.util.function.Consumer;

/**
 * 用一个 id/label + 动作 lambda 快速造一个 {@link Tool}，省去逐个写匿名类的样板。
 * 服务在 {@code onServiceConnected} 里据此注册各工具。
 */
public final class SimpleTool implements Tool {

    private final String id;
    private final String label;
    private final Consumer<SphereAccessibilityService> action;

    public SimpleTool(String id, String label, Consumer<SphereAccessibilityService> action) {
        this.id = id;
        this.label = label;
        this.action = action;
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
        action.accept(svc);
    }
}
