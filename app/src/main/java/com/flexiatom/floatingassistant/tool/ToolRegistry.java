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
