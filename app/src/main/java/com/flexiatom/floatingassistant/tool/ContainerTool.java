/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of FloatingAssistant.
 *
 * Copyright (C) 2026 FlexiAtom
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

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
