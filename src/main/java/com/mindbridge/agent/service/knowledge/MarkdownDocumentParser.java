package com.mindbridge.agent.service.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.markdown.MarkdownRenderer;
import org.springframework.stereotype.Component;

/** Extracts retrieval-relevant structure from a CommonMark AST. */
@Component
public class MarkdownDocumentParser {

    private final List<Extension> extensions = List.of(TablesExtension.create());
    private final Parser parser = Parser.builder().extensions(extensions)
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build();
    private final MarkdownRenderer markdownRenderer = MarkdownRenderer.builder()
            .extensions(extensions)
            .build();

    public MarkdownDocument parse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return MarkdownDocument.empty();
        }
        StructureVisitor visitor = new StructureVisitor(markdownRenderer);
        parser.parse(markdown).accept(visitor);
        return visitor.result();
    }

    public String withImageDescriptions(
            String markdown, List<DocumentParseResult.ImageReference> images
    ) {
        if (markdown == null || markdown.isBlank() || images.isEmpty()) {
            return markdown;
        }
        Map<String, String> descriptions = new HashMap<>();
        for (DocumentParseResult.ImageReference image : images) {
            if (image.path() != null && image.description() != null
                    && image.description().summary() != null && !image.description().summary().isBlank()) {
                descriptions.put(image.path(), image.description().summary());
            }
        }
        if (descriptions.isEmpty()) {
            return markdown;
        }
        Map<Integer, String> insertions = new TreeMap<>(Comparator.reverseOrder());
        parser.parse(markdown).accept(new AbstractVisitor() {
            @Override
            public void visit(Image image) {
                String description = descriptions.get(image.getDestination());
                List<SourceSpan> spans = image.getSourceSpans();
                if (description != null && !spans.isEmpty()) {
                    SourceSpan last = spans.get(spans.size() - 1);
                    int offset = last.getInputIndex() + last.getLength();
                    if (offset >= 0 && offset <= markdown.length()) {
                        insertions.put(offset, " 图片描述：" + escapeInline(description));
                    }
                }
            }
        });
        StringBuilder result = new StringBuilder(markdown);
        insertions.forEach(result::insert);
        return result.toString();
    }

    private String escapeInline(String description) {
        StringBuilder result = new StringBuilder();
        for (char character : description.replaceAll("\\R+", " ").toCharArray()) {
            if ("\\`*_{}[]()#+-.!|>".indexOf(character) >= 0) {
                result.append('\\');
            }
            result.append(character);
        }
        return result.toString();
    }

    private static class StructureVisitor extends AbstractVisitor {

        private final MarkdownRenderer markdownRenderer;
        private final List<MarkdownDocument.Heading> headings = new ArrayList<>();
        private final List<MarkdownDocument.Heading> headingPath = new ArrayList<>();
        private final List<MarkdownDocument.Block> blocks = new ArrayList<>();
        private final List<MarkdownDocument.ImageReference> images = new ArrayList<>();

        private StructureVisitor(MarkdownRenderer markdownRenderer) {
            this.markdownRenderer = markdownRenderer;
        }

        @Override
        public void visit(Heading heading) {
            MarkdownDocument.Heading value = new MarkdownDocument.Heading(
                    heading.getLevel(), text(heading));
            while (!headingPath.isEmpty()
                    && headingPath.get(headingPath.size() - 1).level() >= value.level()) {
                headingPath.remove(headingPath.size() - 1);
            }
            headingPath.add(value);
            headings.add(value);
            visitChildren(heading);
        }

        @Override
        public void visit(Paragraph paragraph) {
            String text = text(paragraph);
            if (!text.isBlank()) {
                blocks.add(new MarkdownDocument.Block(
                        MarkdownDocument.BlockType.PARAGRAPH, text, headingPath));
            }
            visitChildren(paragraph);
        }

        @Override
        public void visit(Image image) {
            images.add(new MarkdownDocument.ImageReference(
                    image.getDestination(), text(image), image.getTitle(), headingPath));
        }

        @Override
        public void visit(FencedCodeBlock codeBlock) {
            addCodeBlock(codeBlock);
        }

        @Override
        public void visit(IndentedCodeBlock codeBlock) {
            addCodeBlock(codeBlock);
        }

        @Override
        public void visit(CustomBlock block) {
            if (block instanceof TableBlock) {
                blocks.add(new MarkdownDocument.Block(
                        MarkdownDocument.BlockType.TABLE,
                        markdownRenderer.render(block).strip(),
                        headingPath));
                visitChildren(block);
                return;
            }
            visitChildren(block);
        }

        private void addCodeBlock(Node codeBlock) {
            blocks.add(new MarkdownDocument.Block(
                    MarkdownDocument.BlockType.CODE,
                    markdownRenderer.render(codeBlock).strip(),
                    headingPath));
        }

        private String text(Node node) {
            StringBuilder text = new StringBuilder();
            appendText(node.getFirstChild(), text);
            return text.toString().strip();
        }

        private void appendText(Node node, StringBuilder text) {
            for (Node current = node; current != null; current = current.getNext()) {
                if (current instanceof Text literal) {
                    text.append(literal.getLiteral());
                } else if (current instanceof Code code) {
                    text.append(code.getLiteral());
                } else if (current instanceof SoftLineBreak || current instanceof HardLineBreak) {
                    text.append('\n');
                } else {
                    appendText(current.getFirstChild(), text);
                }
            }
        }

        private MarkdownDocument result() {
            return new MarkdownDocument(headings, blocks, images);
        }
    }
}
