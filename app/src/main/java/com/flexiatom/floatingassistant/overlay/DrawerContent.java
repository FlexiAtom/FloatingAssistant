package com.flexiatom.floatingassistant.overlay;

import android.graphics.drawable.Drawable;

import java.util.List;

/**
 * 抽屉位的**内容模板**（人裁 2026-10-22「一个抽屉位、N 种内容，显示什么根据按键传入定」）。
 *
 * <p>外壳固定长在 {@link FloatingEditorView}：100dp 左栏、不透明容器面、左圆角、遮罩变暗、
 * 点编辑器一切点击＝只收起、列表区直通到底。按键递进来的就是这「一套东西」——栏头标题、
 * 栏头右上钮（{@code headerIcon == null} 即不显示该钮）、是否带 Search 框、条目集合。
 * 条目 {@code action == null} 是**置灰占位**（不可点），用于功能未接线的档期，菜单形态能整眼看完。
 */
final class DrawerContent {

    /** 抽屉里的一条：文案 + 动作（{@code longAction} 可无＝长按不做事）。 */
    static final class Item {
        final String label;
        final Runnable action;
        final Runnable longAction;

        Item(String label, Runnable action) {
            this(label, action, null);
        }

        Item(String label, Runnable action, Runnable longAction) {
            this.label = label;
            this.action = action;
            this.longAction = longAction;
        }
    }

    final String title;
    final Drawable headerIcon;
    final Runnable headerAction;
    final boolean searchable;
    final List<Item> items;

    DrawerContent(String title, Drawable headerIcon, Runnable headerAction,
                  boolean searchable, List<Item> items) {
        this.title = title;
        this.headerIcon = headerIcon;
        this.headerAction = headerAction;
        this.searchable = searchable;
        this.items = items;
    }
}
