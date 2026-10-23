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
