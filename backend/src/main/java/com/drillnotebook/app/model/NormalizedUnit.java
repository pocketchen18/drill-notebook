package com.drillnotebook.app.model;

/**
 * A single ordered text unit produced by normalizing a TipTap block node.
 *
 * @param text         The rendered plain text of the block (no trailing \n\n separator).
 * @param headingLevel 0 for non-heading blocks, 1–6 for heading blocks.
 * @param headingText  The heading text itself (empty for non-heading blocks).
 */
public record NormalizedUnit(String text, int headingLevel, String headingText) {
    public NormalizedUnit {
        if (headingLevel < 0 || headingLevel > 6) {
            throw new IllegalArgumentException("headingLevel must be 0–6");
        }
        if (headingText == null) headingText = "";
        if (text == null) text = "";
    }

    public boolean isHeading() {
        return headingLevel > 0;
    }
}
