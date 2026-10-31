package org.openpnp.mcp.tools;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The footprints of a KiCad PCB file (KiCad 6 and later), with the outlines that give a part's
 * body size. Graphic coordinates in the file are in the footprint's own frame, before its
 * placement rotation, with Y pointing down.
 */
final class KicadBoard {
    static final String FILE_SUFFIX = ".kicad_pcb";

    /** An axis-aligned box in the footprint frame, in mm, Y up as in OpenPnP. */
    static final class Box {
        final double minX;
        final double minY;
        final double maxX;
        final double maxY;

        Box(double minX, double minY, double maxX, double maxY) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
        }

        double width() {
            return maxX - minX;
        }

        double height() {
            return maxY - minY;
        }

        double centreX() {
            return (minX + maxX) / 2;
        }

        double centreY() {
            return (minY + maxY) / 2;
        }
    }

    static final class Footprint {
        final String reference;
        /** The footprint name without its library, e.g. "SOIC-8_3.9x4.9mm_P1.27mm". */
        final String name;
        final boolean bottom;
        /** The fabrication outline, the drawn part body; null if the footprint has none. */
        final Box fab;
        /** The courtyard, which includes the pads and a clearance; null if none. */
        final Box courtyard;

        Footprint(String reference, String name, boolean bottom, Box fab, Box courtyard) {
            this.reference = reference;
            this.name = name;
            this.bottom = bottom;
            this.fab = fab;
            this.courtyard = courtyard;
        }
    }

    private final List<Footprint> footprints;

    private KicadBoard(List<Footprint> footprints) {
        this.footprints = Collections.unmodifiableList(footprints);
    }

    List<Footprint> getFootprints() {
        return footprints;
    }

    static KicadBoard read(File file) throws IOException {
        if (!file.getName().toLowerCase(Locale.ROOT).endsWith(FILE_SUFFIX)) {
            throw new IllegalArgumentException(file + " is not a " + FILE_SUFFIX + " file");
        }
        if (!file.isFile()) {
            throw new IllegalArgumentException("No file " + file);
        }
        return parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    static KicadBoard parse(String text) {
        Node root = new Parser(text).parse();
        if (!"kicad_pcb".equals(root.head())) {
            throw new IllegalArgumentException("Not a KiCad PCB file");
        }
        if (root.children("footprint").isEmpty() && !root.children("module").isEmpty()) {
            throw new IllegalArgumentException("KiCad 5 boards are not read; save the board in "
                    + "KiCad 6 or later");
        }
        List<Footprint> footprints = new ArrayList<>();
        for (Node node : root.children("footprint")) {
            footprints.add(footprint(node));
        }
        return new KicadBoard(footprints);
    }

    private static Footprint footprint(Node node) {
        String libraryId = node.atom(1);
        String name = libraryId.substring(libraryId.indexOf(':') + 1);
        Node layer = node.child("layer");
        boolean bottom = layer != null && "B.Cu".equals(layer.atom(1));
        String side = bottom ? "B." : "F.";
        return new Footprint(reference(node), name, bottom,
                outline(node, side + "Fab", bottom), outline(node, side + "CrtYd", bottom));
    }

    /** KiCad 8 and later keep it in a property; KiCad 6 and 7 in an fp_text. */
    private static String reference(Node footprint) {
        for (Node property : footprint.children("property")) {
            if ("Reference".equals(property.atom(1))) {
                return property.atom(2);
            }
        }
        for (Node text : footprint.children("fp_text")) {
            if ("reference".equals(text.atom(1))) {
                return text.atom(2);
            }
        }
        return null;
    }

    /**
     * A footprint on the back is stored flipped left to right; mirroring it back gives the
     * library frame that the OpenPnP package uses.
     */
    private static Box outline(Node footprint, String layerName, boolean bottom) {
        BoxBuilder box = new BoxBuilder(bottom);
        for (Node graphic : footprint.children()) {
            String head = graphic.head();
            if (head == null || !head.startsWith("fp_")) {
                continue;
            }
            Node layer = graphic.child("layer");
            if (layer == null || !layerName.equals(layer.atom(1))) {
                continue;
            }
            switch (head) {
                case "fp_line":
                case "fp_rect":
                    box.add(graphic.child("start"));
                    box.add(graphic.child("end"));
                    break;
                case "fp_arc":
                    // Through its end points and midpoint: slightly inside a bulging arc.
                    box.add(graphic.child("start"));
                    box.add(graphic.child("mid"));
                    box.add(graphic.child("end"));
                    break;
                case "fp_circle":
                    box.addCircle(graphic.child("center"), graphic.child("end"));
                    break;
                case "fp_poly":
                    Node points = graphic.child("pts");
                    if (points != null) {
                        for (Node point : points.children("xy")) {
                            box.add(point);
                        }
                    }
                    break;
                default:
                    break;
            }
        }
        return box.build();
    }

    private static final class BoxBuilder {
        private final double xSign;
        private double minX = Double.POSITIVE_INFINITY;
        private double minY = Double.POSITIVE_INFINITY;
        private double maxX = Double.NEGATIVE_INFINITY;
        private double maxY = Double.NEGATIVE_INFINITY;

        BoxBuilder(boolean mirrored) {
            xSign = mirrored ? -1 : 1;
        }

        void add(Node point) {
            if (point != null) {
                add(xSign * point.number(1), -point.number(2));
            }
        }

        void addCircle(Node centre, Node edge) {
            if (centre == null || edge == null) {
                return;
            }
            double radius = Math.hypot(edge.number(1) - centre.number(1),
                    edge.number(2) - centre.number(2));
            add(xSign * centre.number(1) - radius, -centre.number(2) - radius);
            add(xSign * centre.number(1) + radius, -centre.number(2) + radius);
        }

        private void add(double x, double y) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }

        Box build() {
            if (minX > maxX || minY > maxY) {
                return null;
            }
            return new Box(minX, minY, maxX, maxY);
        }
    }

    /** An S-expression: an atom, or a list whose first element is usually its name. */
    static final class Node {
        private final String atom;
        private final List<Node> children;

        private Node(String atom, List<Node> children) {
            this.atom = atom;
            this.children = children;
        }

        String head() {
            return atom(0);
        }

        /** The atom at a position in this list, or null. */
        String atom(int index) {
            if (children == null || index >= children.size()) {
                return null;
            }
            return children.get(index).atom;
        }

        double number(int index) {
            String value = atom(index);
            if (value == null) {
                throw new IllegalArgumentException("Missing coordinate in (" + head() + ")");
            }
            try {
                return Double.parseDouble(value);
            }
            catch (NumberFormatException e) {
                throw new IllegalArgumentException("Bad number " + value + " in (" + head() + ")");
            }
        }

        List<Node> children() {
            return children == null ? List.of() : children;
        }

        Node child(String name) {
            for (Node child : children()) {
                if (name.equals(child.head())) {
                    return child;
                }
            }
            return null;
        }

        List<Node> children(String name) {
            List<Node> matches = new ArrayList<>();
            for (Node child : children()) {
                if (name.equals(child.head())) {
                    matches.add(child);
                }
            }
            return matches;
        }
    }

    private static final class Parser {
        private final String text;
        private int position;

        Parser(String text) {
            this.text = text;
        }

        Node parse() {
            skipWhitespace();
            if (position >= text.length() || text.charAt(position) != '(') {
                throw new IllegalArgumentException("Not a KiCad PCB file");
            }
            return list();
        }

        /** Iterative, because KiCad nests deeply enough in zones to matter for recursion. */
        private Node list() {
            List<List<Node>> stack = new ArrayList<>();
            List<Node> current = null;
            while (true) {
                skipWhitespace();
                if (position >= text.length()) {
                    throw new IllegalArgumentException("Unexpected end of the KiCad file");
                }
                char c = text.charAt(position);
                if (c == '(') {
                    position++;
                    if (current != null) {
                        stack.add(current);
                    }
                    current = new ArrayList<>();
                }
                else if (c == ')') {
                    position++;
                    Node node = new Node(null, current);
                    if (stack.isEmpty()) {
                        return node;
                    }
                    current = stack.remove(stack.size() - 1);
                    current.add(node);
                }
                else {
                    current.add(new Node(c == '"' ? quoted() : bare(), null));
                }
            }
        }

        private String quoted() {
            StringBuilder value = new StringBuilder();
            position++;
            while (position < text.length()) {
                char c = text.charAt(position++);
                if (c == '"') {
                    return value.toString();
                }
                if (c == '\\' && position < text.length()) {
                    c = text.charAt(position++);
                }
                value.append(c);
            }
            throw new IllegalArgumentException("Unterminated string in the KiCad file");
        }

        private String bare() {
            int start = position;
            while (position < text.length()) {
                char c = text.charAt(position);
                if (Character.isWhitespace(c) || c == '(' || c == ')') {
                    break;
                }
                position++;
            }
            return text.substring(start, position);
        }

        private void skipWhitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }
    }
}
