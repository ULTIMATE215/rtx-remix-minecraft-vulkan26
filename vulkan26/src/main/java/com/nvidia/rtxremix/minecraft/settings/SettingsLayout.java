/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

/** Pixel layout shared by the flat settings screen and its bounds checks. */
record SettingsLayout(int left, int right, int tabsY, int top, int bottom,
                      int listRight, int footerY, int descriptionX) {
    static SettingsLayout of(int width, int height) {
        int panelWidth = Math.min(900, Math.max(240, width - 24));
        int left = (width - panelWidth) / 2;
        int right = left + panelWidth;
        boolean details = panelWidth >= 550;
        int listRight = details ? right - Math.min(240, panelWidth / 3) - 8 : right;
        return new SettingsLayout(left, right, 29, 58, height - 44,
                listRight, height - 32, details ? listRight + 8 : -1);
    }

    int listWidth() { return listRight - left; }
    int bodyHeight() { return bottom - top; }
    int footerWidth() { return (right - left - 18) / 4; }
    boolean hasDescription() { return descriptionX >= 0; }
    double clampScroll(double scroll, int contentHeight) {
        return Math.max(0, Math.min(scroll, Math.max(0, contentHeight - bodyHeight())));
    }
}
