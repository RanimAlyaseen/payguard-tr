package com.payguard.verify;

import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.result.FindingType;
import com.payguard.core.spec.SpecDecisionTable;
import com.payguard.core.spec.SpecDecisionTable.SpecCell;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks {@link SpecDecisionTable} against the markdown table in {@code requirements.md} §4.2.
 *
 * <p><strong>Provenance, stated plainly.</strong> {@code SpecDecisionTable.java} was originally
 * produced by an ad-hoc script that was not kept, so this is <em>not</em> that generator and does
 * not claim to be. It is a checker written afterwards to make the claim verifiable rather than
 * merely asserted: it re-reads the specification document and compares every cell against the
 * committed Java, so "the expected values come from requirements §4.2" is now something a reader
 * can run instead of something they have to take on trust.
 *
 * <p>It re-parses the document on every run, so editing §4.2 without regenerating the Java is
 * caught here rather than silently diverging.
 *
 * <p>Run: {@code java -cp build/classes:build/verify com.payguard.verify.SpecTableChecker
 * ../docs/architecture/requirements.md}
 */
public final class SpecTableChecker {

    private static final String DEFAULT_PATH = "../docs/architecture/requirements.md";

    public static void main(String[] args) throws IOException {
        Path path = Path.of(args.length > 0 ? args[0] : DEFAULT_PATH);
        System.out.println("Specification decision-table checker");
        System.out.println("source: " + path.toAbsolutePath().normalize());
        System.out.println("-".repeat(70));

        List<String> rows = tableRows(Files.readAllLines(path));
        if (rows.size() != PaymentStatus.values().length) {
            fail("expected " + PaymentStatus.values().length + " data rows, found " + rows.size());
        }

        int cells = 0, fixed = 0, conditional = 0, agreement = 0, mismatches = 0;

        for (String row : rows) {
            List<String> parts = cellsOf(row);
            String rowLabel = unquote(parts.get(0));
            PaymentStatus payment = PaymentStatus.valueOf(rowLabel);
            if (parts.size() - 1 != OrderStatus.values().length) {
                fail(rowLabel + ": expected " + OrderStatus.values().length + " cells, found " + (parts.size() - 1));
            }
            for (int column = 0; column < OrderStatus.values().length; column++) {
                OrderStatus order = OrderStatus.values()[column];
                String raw = unquote(parts.get(column + 1));
                cells++;

                SpecCell committed = SpecDecisionTable.expected(payment, order);
                String committedShape;
                String documentShape;

                if (raw.equals("[A]")) {
                    agreement++;
                    documentShape = "agreement";
                    committedShape = committed instanceof SpecCell.Agreement ? "agreement" : describe(committed);
                } else if (raw.startsWith("[T:")) {
                    conditional++;
                    String[] halves = raw.substring(3, raw.length() - 1).split("/");
                    documentShape = "conditional:" + halves[0] + "/" + halves[1];
                    committedShape =
                            committed instanceof SpecCell.Conditional c
                                    ? "conditional:" + c.below() + "/" + c.atOrAbove()
                                    : describe(committed);
                } else {
                    fixed++;
                    FindingType type = FindingType.valueOf(raw);
                    documentShape = "fixed:" + type;
                    committedShape =
                            committed instanceof SpecCell.Fixed f ? "fixed:" + f.type() : describe(committed);
                }

                if (!documentShape.equals(committedShape)) {
                    mismatches++;
                    System.out.println(
                            "  MISMATCH " + payment + "/" + order
                                    + "\n      document: " + documentShape
                                    + "\n      committed: " + committedShape);
                }
            }
        }

        System.out.println("cells in document      : " + cells);
        System.out.println("cells in SpecDecisionTable: " + SpecDecisionTable.CELL_COUNT);
        System.out.println("  fixed                : " + fixed);
        System.out.println("  threshold-conditional: " + conditional);
        System.out.println("  agreement            : " + agreement);
        System.out.println("mismatches             : " + mismatches);

        boolean ok = true;
        ok &= expect("cell count is 8 x 10", cells == 80);
        ok &= expect("committed table agrees on size", SpecDecisionTable.CELL_COUNT == cells);
        ok &= expect("66 fixed cells", fixed == 66);
        ok &= expect("12 threshold-conditional cells", conditional == 12);
        ok &= expect("2 agreement cells", agreement == 2);
        ok &= expect("no cell differs from the document", mismatches == 0);

        System.out.println("-".repeat(70));
        System.out.println(ok ? "SPEC TABLE OK" : "SPEC TABLE MISMATCH");
        System.exit(ok ? 0 : 1);
    }

    private static boolean expect(String what, boolean condition) {
        System.out.println((condition ? "  ok   " : "  FAIL ") + what);
        return condition;
    }

    private static void fail(String message) {
        System.out.println("FATAL: " + message);
        System.exit(1);
    }

    /** The data rows of the first markdown table whose header starts with the payment/order axis. */
    private static List<String> tableRows(List<String> lines) {
        List<String> rows = new ArrayList<>();
        boolean inTable = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("| Payment")) {
                inTable = true;
                continue;
            }
            if (inTable) {
                if (trimmed.startsWith("|---")) {
                    continue;
                }
                if (!trimmed.startsWith("|")) {
                    break;
                }
                rows.add(trimmed);
            }
        }
        return rows;
    }

    private static List<String> cellsOf(String row) {
        String body = row.substring(1, row.lastIndexOf('|'));
        List<String> out = new ArrayList<>();
        for (String cell : body.split("\\|")) {
            out.add(cell.trim());
        }
        return out;
    }

    private static String unquote(String cell) {
        return cell.replace("`", "").trim();
    }

    private static String describe(SpecCell cell) {
        if (cell instanceof SpecCell.Fixed f) {
            return "fixed:" + f.type();
        }
        if (cell instanceof SpecCell.Agreement) {
            return "agreement";
        }
        if (cell instanceof SpecCell.Conditional c) {
            return "conditional:" + c.below() + "/" + c.atOrAbove();
        }
        return "unknown";
    }
}
