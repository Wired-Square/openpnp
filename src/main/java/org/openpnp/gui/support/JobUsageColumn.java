package org.openpnp.gui.support;

import java.awt.Color;
import java.awt.Component;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.Comparator;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;

import org.openpnp.Translations;
import org.openpnp.model.JobUsageService.Issue;
import org.openpnp.model.JobUsageService.Usage;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.util.UIScale;

/**
 * Shows a {@link Usage} column as an icon whose shape and colour both carry the state: a green
 * tick when the open job needs the row and nothing stops it, an amber mark when something does
 * (named in the tooltip), and a grey ring when the job does not need it.
 */
public final class JobUsageColumn {
    private static final int ICON_SIZE = 16;
    private static final int COLUMN_PADDING = 12;
    private static final String USER_SCALE_FACTOR = "userScaleFactor"; //$NON-NLS-1$

    static final Icon IN_USE = statusIcon("icons/check-circle.svg", UIColors::usageInUse); //$NON-NLS-1$
    static final Icon ISSUE = statusIcon("icons/error.svg", UIColors::usageIssue); //$NON-NLS-1$
    static final Icon NOT_IN_USE =
            statusIcon("icons/radio-button-unchecked.svg", UIColors::usageNotInUse); //$NON-NLS-1$

    // Declared in attention order, which the column sorts by.
    enum State {
        ISSUE,
        IN_USE,
        NOT_IN_USE
    }

    static final Comparator<Usage> BY_ATTENTION = Comparator.comparing(JobUsageColumn::state);

    private JobUsageColumn() {
    }

    /**
     * Renders the model column's Usage values, sorts it by attention, explains it in the header
     * tooltip and shows it first and narrow.
     */
    public static void install(JTable table, TableRowSorter<?> sorter, int modelColumn) {
        sorter.setComparator(modelColumn, BY_ATTENTION);

        TableColumn column =
                table.getColumnModel().getColumn(table.convertColumnIndexToView(modelColumn));
        column.setCellRenderer(new Renderer());
        installWidth(column);
        column.setResizable(false);
        table.getColumnModel().moveColumn(table.convertColumnIndexToView(modelColumn), 0);

        installHeaderToolTip(table, modelColumn);
    }

    /*
     * The header's renderer component is shared by every column, so the tooltip is the header's
     * own, switched as the pointer moves. It is set as the client property because
     * setToolTipText(null) would unregister the header from the tooltip manager mid-hover.
     */
    private static void installHeaderToolTip(JTable table, int modelColumn) {
        String toolTip = Translations.getString("JobUsageColumn.Header.toolTip"); //$NON-NLS-1$
        JTableHeader header = table.getTableHeader();
        header.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                int column = header.columnAtPoint(event.getPoint());
                boolean usageColumn =
                        column >= 0 && table.convertColumnIndexToModel(column) == modelColumn;
                header.putClientProperty(JComponent.TOOL_TIP_TEXT_KEY,
                        usageColumn ? toolTip : null);
            }
        });
    }

    // FlatLaf rescales the icon when the user changes the zoom, so the column follows.
    private static void installWidth(TableColumn column) {
        applyWidth(column);
        UIScale.addPropertyChangeListener(event -> {
            if (USER_SCALE_FACTOR.equals(event.getPropertyName())) {
                applyWidth(column);
            }
        });
    }

    // setMinWidth clamps to the current maximum, so release the minimum before widening.
    private static void applyWidth(TableColumn column) {
        int width = UIScale.scale(ICON_SIZE + COLUMN_PADDING);
        column.setMinWidth(0);
        column.setMaxWidth(width);
        column.setMinWidth(width);
        column.setPreferredWidth(width);
    }

    static State state(Usage usage) {
        if (!usage.isInUse()) {
            return State.NOT_IN_USE;
        }
        return usage.getIssues().isEmpty() ? State.IN_USE : State.ISSUE;
    }

    static Icon icon(Usage usage) {
        switch (state(usage)) {
            case ISSUE:
                return ISSUE;
            case IN_USE:
                return IN_USE;
            default:
                return NOT_IN_USE;
        }
    }

    static String toolTip(Usage usage) {
        switch (state(usage)) {
            case NOT_IN_USE:
                return Translations.getString("JobUsageColumn.NotInUse.toolTip"); //$NON-NLS-1$
            case IN_USE:
                return Translations.getString("JobUsageColumn.InUse.toolTip"); //$NON-NLS-1$
            default:
                break;
        }
        String issues = usage.getIssues().stream()
                .map(JobUsageColumn::describe)
                .collect(Collectors.joining("; ")); //$NON-NLS-1$
        return String.format(Translations.getString("JobUsageColumn.Issues.toolTip"), issues); //$NON-NLS-1$
    }

    private static String describe(Issue issue) {
        switch (issue) {
            case PART_HEIGHT_UNKNOWN:
                return Translations.getString("JobUsageColumn.Issue.PartHeightUnknown"); //$NON-NLS-1$
            case NO_COMPATIBLE_NOZZLE_TIP:
                return Translations.getString("JobUsageColumn.Issue.NoCompatibleNozzleTip"); //$NON-NLS-1$
            case NO_ENABLED_FEEDER:
                return Translations.getString("JobUsageColumn.Issue.NoEnabledFeeder"); //$NON-NLS-1$
            case FEEDER_DISABLED:
                return Translations.getString("JobUsageColumn.Issue.FeederDisabled"); //$NON-NLS-1$
            default:
                return issue.name();
        }
    }

    // Recoloured at paint time, so a live theme switch picks up the other palette.
    private static Icon statusIcon(String resource, Supplier<Color> color) {
        FlatSVGIcon icon = new FlatSVGIcon(resource, ICON_SIZE, ICON_SIZE);
        icon.setColorFilter(new FlatSVGIcon.ColorFilter(original -> color.get()));
        return icon;
    }

    static final class Renderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        Renderer() {
            setHorizontalAlignment(SwingConstants.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, null, isSelected, hasFocus, row, column);
            Usage usage = value instanceof Usage ? (Usage) value : Usage.NOT_IN_USE;
            setIcon(icon(usage));
            setToolTipText(toolTip(usage));
            return this;
        }
    }
}
