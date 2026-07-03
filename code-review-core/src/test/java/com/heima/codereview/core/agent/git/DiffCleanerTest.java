package com.heima.codereview.core.agent.git;

import com.heima.codereview.tools.git.DiffCleaner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffCleanerTest {

    @Test
    void removesIndexAndMarkersAndKeepsCodeOnly() {
        String raw = """
                # Git审查上下文
                策略: 本地仓库最近 2 次提交

                ## 最近提交
                abc1234 fix
                def5678 feat

                ## 代码差异
                diff --git a/src/services/content_renderer_text.ts b/src/services/content_renderer_text.ts
                index 7c6f8a9..a1b2c3d 100644
                --- a/src/services/content_renderer_text.ts
                +++ b/src/services/content_renderer_text.ts
                @@ -57,6 +57,7 @@
                     await rewriteMermaidDiagramsInContainer($renderedContent[0] as HTMLDivElement);
                +    await applyInlineMermaid($renderedContent[0] as HTMLDivElement);
                     await formatCodeBlocks($renderedContent);
                """;

        String cleaned = DiffCleaner.clean(raw);

        assertTrue(cleaned.contains("src/services/content_renderer_text.ts"));
        assertTrue(cleaned.contains("@@ -57,6 +57,7 @@"));
        assertTrue(cleaned.contains("+    await applyInlineMermaid"));
        assertFalse(cleaned.contains("# Git审查上下文"), "should remove review preamble");
        assertFalse(cleaned.contains("index 7c6f8a9"), "should remove index line");
        assertFalse(cleaned.contains("--- a/"), "should remove --- marker");
        assertFalse(cleaned.contains("+++ b/"), "should remove +++ marker");
        assertFalse(cleaned.contains("abc1234"), "should remove commit log");
    }

    @Test
    void keepsMultipleFilesAndHunks() {
        String raw = """
                diff --git a/src/foo.ts b/src/foo.ts
                index 111..222 100644
                --- a/src/foo.ts
                +++ b/src/foo.ts
                @@ -1,3 +1,4 @@
                 line1
                +line2
                 line3
                @@ -10,2 +11,3 @@
                 old
                -removed
                +added
                diff --git a/src/bar.ts b/src/bar.ts
                index 333..444 100644
                --- a/src/bar.ts
                +++ b/src/bar.ts
                @@ -5,6 +5,7 @@
                     context
                +    new line
                     context
                """;

        String cleaned = DiffCleaner.clean(raw);

        String[] lines = cleaned.split("\n");
        assertEquals("src/foo.ts", lines[0]);
        assertEquals("@@ -1,3 +1,4 @@", lines[1]);
        assertEquals("src/bar.ts", lines[9]);
        assertEquals("@@ -5,6 +5,7 @@", lines[10]);
        assertFalse(cleaned.contains("index "));
    }

    @Test
    void emptyOrBlankInputReturnsEmpty() {
        assertEquals("", DiffCleaner.clean(""));
        assertEquals("", DiffCleaner.clean("   \n\n  "));
        assertEquals("", DiffCleaner.clean(null));
    }

    @Test
    void binaryFileProducesEmptyWhenNoHunks() {
        String raw = """
                diff --git a/assets/logo.png b/assets/logo.png
                index 111..222
                Binary files differ
                """;
        assertEquals("", DiffCleaner.clean(raw));
    }

    @Test
    void removesHunkFunctionNameAfterAtAt() {
        String raw = """
                diff --git a/src/x.ts b/src/x.ts
                index 1..2 100644
                --- a/src/x.ts
                +++ b/src/x.ts
                @@ -112,7 +112,7 @@ export function renderMermaidBlock(div: HTMLDivElement) {
                     const inner = div.innerHTML;
                -    div.textContent = oldValue;
                +    div.textContent = newValue;
                """;

        String cleaned = DiffCleaner.clean(raw);

        assertTrue(cleaned.contains("@@ -112,7 +112,7 @@"));
        assertFalse(cleaned.contains("export function renderMermaidBlock"), "should remove function context after hunk header");
    }
}
