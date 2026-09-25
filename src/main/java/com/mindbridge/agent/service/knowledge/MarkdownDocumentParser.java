package com.mindbridge.agent.service.knowledge;

import java.util.ArrayList;
import java.util.List;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.Image;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;

/** Extracts retrieval-relevant structure from a CommonMark AST. */
@Component
public class MarkdownDocumentParser {

    private final Parser parser = Parser.builder().build();

    public MarkdownDocument parse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return MarkdownDocument.empty();
        }
        StructureVisitor visitor = new StructureVisitor();
        parser.parse(markdown).accept(visitor);
        return visitor.result();
    }

    private static class StructureVisitor extends AbstractVisitor {

        private final List<MarkdownDocument.Heading> headings = new ArrayList<>();
        private final List<MarkdownDocument.Heading> headingPath = new ArrayList<>();
        private final List<MarkdownDocument.Block> blocks = new ArrayList<>();
        private final List<MarkdownDocument.ImageReference> images = new ArrayList<>();

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
