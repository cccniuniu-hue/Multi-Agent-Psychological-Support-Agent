package com.mindbridge.agent.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownDocumentParserTests {

    @Test
    void extractsHeadingHierarchyParagraphsAndImages() {
        String markdown = """
                # Guide

                Intro with **bold** text.

                ## Sleep

                Try a [routine](https://example.com).

                ![Breathing chart](images/breath.png "Breathing")
                """;

        MarkdownDocument document = new MarkdownDocumentParser().parse(markdown);

        MarkdownDocument.Heading guide = new MarkdownDocument.Heading(1, "Guide");
        MarkdownDocument.Heading sleep = new MarkdownDocument.Heading(2, "Sleep");
        assertThat(document.headings()).containsExactly(guide, sleep);
        assertThat(document.blocks()).extracting(MarkdownDocument.Block::text)
                .containsExactly("Intro with bold text.", "Try a routine.", "Breathing chart");
        assertThat(document.blocks().get(0).headingPath()).containsExactly(guide);
        assertThat(document.blocks().get(1).headingPath()).containsExactly(guide, sleep);
        assertThat(document.images()).containsExactly(new MarkdownDocument.ImageReference(
                "images/breath.png", "Breathing chart", "Breathing", List.of(guide, sleep)));
    }

    @Test
    void extractsGfmTableAsOneStructuredBlock() {
        String markdown = """
                # Contacts

                | Name | Phone |
                | --- | --- |
                | Center | 12345 |
                """;

        MarkdownDocument document = new MarkdownDocumentParser().parse(markdown);

        assertThat(document.blocks()).containsExactly(new MarkdownDocument.Block(
                MarkdownDocument.BlockType.TABLE,
                "|Name|Phone|\n|---|---|\n|Center|12345|",
                List.of(new MarkdownDocument.Heading(1, "Contacts"))));
    }

    @Test
    void extractsImageReferenceInsideTable() {
        MarkdownDocument document = new MarkdownDocumentParser().parse("""
                | Resource |
                | --- |
                | ![Campus map](images/map.png) |
                """);

        assertThat(document.images()).containsExactly(new MarkdownDocument.ImageReference(
                "images/map.png", "Campus map", null, List.of()));
    }

    @Test
    void keepsFencedCodeAsAnIndependentBlock() {
        MarkdownDocument document = new MarkdownDocumentParser().parse("""
                # API

                Before code.

                ```java
                System.out.println("hello");
                ```

                After code.
                """);

        assertThat(document.blocks()).extracting(MarkdownDocument.Block::type)
                .containsExactly(
                        MarkdownDocument.BlockType.PARAGRAPH,
                        MarkdownDocument.BlockType.CODE,
                        MarkdownDocument.BlockType.PARAGRAPH);
        assertThat(document.blocks().get(1).text())
                .isEqualTo("```java\nSystem.out.println(\"hello\");\n```");
    }

    @Test
    void insertsDescriptionAfterExactImageReferencesWithoutChangingOtherMarkdown() {
        String markdown = """
                # Charts

                Before ![First](images/first/chart.png "Week") and ![Other](images/other/chart.png).

                | Chart |
                | --- |
                | ![Again](images/first/chart.png) |

                ```md
                ![Example](images/first/chart.png)
                ```
                """;
        ImageDescription description = new ImageDescription("chart", "Peak | [week]\n# two",
                List.of(), List.of(), List.of());
        var first = new DocumentParseResult.ImageReference("images/first/chart.png", null, null, description);
        var failed = new DocumentParseResult.ImageReference("images/other/chart.png", null);

        String enriched = new MarkdownDocumentParser().withImageDescriptions(markdown, List.of(first, failed));

        assertThat(enriched).isEqualTo("""
                # Charts

                Before ![First](images/first/chart.png "Week") 图片描述：Peak \\| \\[week\\] \\# two and ![Other](images/other/chart.png).

                | Chart |
                | --- |
                | ![Again](images/first/chart.png) 图片描述：Peak \\| \\[week\\] \\# two |

                ```md
                ![Example](images/first/chart.png)
                ```
                """);
        assertThat(new MarkdownDocumentParser().parse(enriched).headings())
                .containsExactly(new MarkdownDocument.Heading(1, "Charts"));
    }
}
