/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettingsLayoutTest {
    @Test void tabsListAndFooterFitAtCommonGuiScales() {
        for (int width : new int[]{320,427,640,854,1280,1920}) for (int height : new int[]{240,360,480,720,1080}) {
            var layout = SettingsLayout.of(width,height);
            assertTrue(layout.left() >= 0 && layout.right() <= width);
            assertTrue(layout.tabsY() + 22 < layout.top());
            assertTrue(layout.top() + 25 < layout.bottom());
            assertTrue(layout.bottom() < layout.footerY());
            assertTrue(layout.footerY() + 22 <= height);
            assertTrue(layout.left() + 4 * layout.footerWidth() + 18 <= layout.right());
            if (layout.hasDescription()) assertTrue(layout.descriptionX() > layout.listRight());
        }
    }
    @Test void smallWindowsUseTooltipsAndWideWindowsHaveADescriptionPanel() {
        assertFalse(SettingsLayout.of(427,240).hasDescription());
        assertTrue(SettingsLayout.of(854,480).hasDescription());
    }
    @Test void scrollingClampsAfterResizeAndForShortCategories() {
        var layout = SettingsLayout.of(427,240);
        assertEquals(0,layout.clampScroll(-100,1000));
        assertEquals(1000 - layout.bodyHeight(),layout.clampScroll(9999,1000));
        assertEquals(0,layout.clampScroll(400,25));
    }
}
