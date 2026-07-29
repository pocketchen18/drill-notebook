package com.drillnotebook.app.service;

import com.drillnotebook.app.model.Chunk;
import com.drillnotebook.app.model.NormalizedUnit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic chunker that implements the 2600-base / 300-tail / 200-overlap
 * algorithm from the notebook retrieval Canonical Contracts.
 *
 * <p>Ordered text units ({@link NormalizedUnit}) are concatenated with
 * {@code \n\n} separators into a full text. Base segments of at most 2600
 * UTF-16 code units divide the full text at unit boundaries. A single
 * overlong unit is hard-windowed. The final base segment is merged with its
 * predecessor if it is shorter than 300 chars (and merging stays ≤2600).
 * Overlap of min(200, previous base length) is prepended to every chunk
 * after the first, keeping every final chunk ≤2800 chars.</p>
 */
public class NoteChunker {

    static final int BASE_MAX = 2600;
    static final int TAIL_MIN = 300;
    static final int OVERLAP_MAX = 200;

    private static final String SEP = "\n\n";

    /**
     * Chunk normalized text units for a given page title.
     *
     * @param units     ordered text units from {@link NoteNormalizer}
     * @param pageTitle the notebook page title
     * @return list of chunks, possibly empty
     */
    public List<Chunk> chunk(List<NormalizedUnit> units, String pageTitle) {
        if (units == null || units.isEmpty()) {
            return List.of();
        }
        // Filter out blank-text units (should already be done by NoteNormalizer,
        // but be defensive for direct callers).
        List<NormalizedUnit> effective = units.stream()
                .filter(u -> u.text() != null && !u.text().isBlank())
                .toList();
        if (effective.isEmpty()) {
            return List.of();
        }
        String safeTitle = pageTitle == null ? "" : pageTitle;

        // 1. Build full text and unit metadata arrays
        String fullText = buildFullText(effective);
        int n = effective.size();
        int[] uStart = new int[n];
        int[] uEnd   = new int[n];
        int[] uLevel = new int[n];
        String[] uHeading = new String[n];
        buildUnitArrays(effective, fullText, uStart, uEnd, uLevel, uHeading);

        // 2. Compute the page-level content hash once (identical for every chunk from this page)
        String pageHash = sha256(fullText);

        // 3. Build base segments (ranges into fullText)
        List<BaseSeg> bases = buildBaseSegments(fullText, uStart, uEnd);

        // 4. Merge small tail
        bases = mergeTail(bases);

        // 5. Build final chunks with overlap
        return buildChunks(bases, fullText, uStart, uLevel, uHeading, safeTitle, pageHash);
    }

    // ── Full text ──────────────────────────────────────────────────────────

    static String buildFullText(List<NormalizedUnit> units) {
        if (units.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < units.size(); i++) {
            if (i > 0) sb.append(SEP);
            sb.append(units.get(i).text());
        }
        return sb.toString();
    }

    /** Fill parallel arrays: start offset, end offset, heading level, heading text. */
    static void buildUnitArrays(
            List<NormalizedUnit> units, String fullText,
            int[] uStart, int[] uEnd,
            int[] uLevel, String[] uHeading) {
        int pos = 0;
        for (int i = 0; i < units.size(); i++) {
            NormalizedUnit u = units.get(i);
            uStart[i] = pos;
            pos += u.text().length();
            uEnd[i] = pos;
            uLevel[i] = u.headingLevel();
            uHeading[i] = u.headingText();
            if (i + 1 < units.size()) pos += SEP.length();
        }
    }

    // ── Base segments ──────────────────────────────────────────────────────

    record BaseSeg(int startOff, int endOff, int firstIdx, int lastIdx) {
        int length() { return endOff - startOff; }
    }

    /**
     * Build base segments by appending whole units until the accumulated
     * length (including \n\n separators) exceeds BASE_MAX.
     */
    static List<BaseSeg> buildBaseSegments(String fullText, int[] uStart, int[] uEnd) {
        List<BaseSeg> result = new ArrayList<>();
        int n = uStart.length;
        if (n == 0) return result;

        int segStart = uStart[0];
        int segFirst = 0;
        int acc = 0;

        for (int i = 0; i < n; i++) {
            int uLen = uEnd[i] - uStart[i];
            int sep = (i > segFirst) ? SEP.length() : 0;

            if (acc > 0 && acc + sep + uLen > BASE_MAX) {
                result.add(new BaseSeg(segStart, uEnd[i - 1], segFirst, i - 1));
                segStart = uStart[i];
                segFirst = i;
                acc = 0;
                sep = 0;
            }

            acc += sep + uLen;
        }

        if (segFirst < n) {
            result.add(new BaseSeg(segStart, uEnd[n - 1], segFirst, n - 1));
        }

        return splitLongUnits(result, fullText);
    }

    /** Hard-window single-unit segments exceeding BASE_MAX. */
    private static List<BaseSeg> splitLongUnits(List<BaseSeg> segs, String fullText) {
        List<BaseSeg> out = new ArrayList<>();
        for (BaseSeg s : segs) {
            if (s.firstIdx == s.lastIdx && s.length() > BASE_MAX) {
                String txt = fullText.substring(s.startOff, s.endOff);
                int p = 0;
                while (p < txt.length()) {
                    int e = Math.min(p + BASE_MAX, txt.length());
                    out.add(new BaseSeg(s.startOff + p, s.startOff + e, s.firstIdx, s.lastIdx));
                    p = e;
                }
            } else {
                out.add(s);
            }
        }
        return out;
    }

    // ── Tail merge ─────────────────────────────────────────────────────────

    static List<BaseSeg> mergeTail(List<BaseSeg> segs) {
        if (segs.size() < 2) return segs;
        List<BaseSeg> r = new ArrayList<>(segs);
        int li = r.size() - 1;
        BaseSeg last = r.get(li);
        BaseSeg prev = r.get(li - 1);
        if (last.length() < TAIL_MIN
                && prev.length() + SEP.length() + last.length() <= BASE_MAX) {
            r.set(li - 1, new BaseSeg(prev.startOff, last.endOff,
                    prev.firstIdx, last.lastIdx));
            r.remove(li);
        }
        return r;
    }

    // ── Chunks with overlap ────────────────────────────────────────────────

    private List<Chunk> buildChunks(
            List<BaseSeg> bases, String fullText,
            int[] uStart, int[] uLevel, String[] uHeading,
            String title, String pageHash) {

        List<Chunk> out = new ArrayList<>();
        for (int i = 0; i < bases.size(); i++) {
            BaseSeg b = bases.get(i);
            int cStart;
            if (i == 0) {
                cStart = b.startOff;
            } else {
                BaseSeg prev = bases.get(i - 1);
                cStart = Math.max(0, prev.endOff - Math.min(OVERLAP_MAX, prev.length()));
            }
            int cEnd = b.endOff;
            String text = fullText.substring(cStart, cEnd);
            // Heading path at the base segment's own start (not the overlap-extended cStart)
            List<String> hp = headingPathAt(b.startOff, uStart, uLevel, uHeading);

            out.add(new Chunk(i, title, hp, text, cStart, cEnd, pageHash));
        }
        return out;
    }

    // ── Heading path ───────────────────────────────────────────────────────

    static List<String> headingPathAt(
            int pos, int[] uStart, int[] uLevel, String[] uHeading) {
        // stack entries: [text, level]
        List<String[]> stack = new ArrayList<>();
        for (int i = 0; i < uStart.length; i++) {
            if (uStart[i] > pos) break;
            int lvl = uLevel[i];
            if (lvl > 0) {
                while (!stack.isEmpty() && stack.get(stack.size() - 1)[1].charAt(0) - '0' >= lvl) {
                    stack.remove(stack.size() - 1);
                }
                stack.add(new String[]{uHeading[i], String.valueOf(lvl)});
            }
        }
        return stack.stream().map(e -> e[0]).toList();
    }

    // ── SHA-256 ────────────────────────────────────────────────────────────

    static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : h) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
