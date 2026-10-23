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

import java.util.Collections;
import java.util.List;

/**
 * 悬浮球扇形菜单里的一个工具（提案 §10 M0：注册即出现在菜单）。
 * 注册顺序即扇形自上而下的排布顺序。
 *
 * <p>两态（由子类/构造决定，逐条目各定）：
 * <ul>
 *   <li><b>叶子（触发）</b>：{@link #children()} 为空 → 选中即 {@link #run} 执行动作，菜单收起。</li>
 *   <li><b>容器（展开子页面）</b>：{@link #children()} 非空 → 选中不执行，而是在当前扇形外**再加画一圈同心环**展开这些子项（径向树，环数=深度）。</li>
 * </ul>
 * 容器实数据与同心环页面栈（push/pop + 枢纽=返回）为下一步；当前三工具全是叶子。
 */
public interface Tool {

    /** 稳定标识（供日志/将来持久化排序用）。 */
    String id();

    /** 扇形楔形格里显示的文字。 */
    String label();

    /** 用户选中该工具时执行（仅叶子态会被调用；容器态由菜单转向展开 children）。 */
    void run(SphereAccessibilityService svc);

    /** 子工具（容器态非空）。默认空 = 叶子。 */
    default List<Tool> children() {
        return Collections.emptyList();
    }

    /** 是否为容器（选中后展开子页面而非触发动作）。 */
    default boolean isContainer() {
        return !children().isEmpty();
    }
}
