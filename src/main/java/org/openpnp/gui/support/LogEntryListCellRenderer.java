package org.openpnp.gui.support;

import org.pmw.tinylog.LogEntry;

import javax.swing.*;

import java.awt.*;

public class LogEntryListCellRenderer extends JTextField implements ListCellRenderer<LogEntry> {
    
    @Override
    public Component getListCellRendererComponent(JList<? extends LogEntry> list, LogEntry logEntry, int index, boolean isSelected, boolean cellHasFocus) {

        if (logEntry == null) {
            return this;
        }

        this.setText(logEntry.getRenderedLogEntry());
        this.setFont(UIManager.getFont("monospaced.font"));
        this.setBorder(null);

        if (isSelected) {
            setBackground(list.getSelectionBackground());
            setForeground(list.getSelectionForeground());
        } else {
            switch(logEntry.getLevel()) {
                case ERROR:
                    setBackground(UIColors.logErrorBg());
                    break;
                default:
                    setBackground(list.getBackground());
            }
            switch(logEntry.getLevel()) {
                case TRACE:
                    setForeground(UIColors.logTrace());
                    break;
                case DEBUG:
                    setForeground(UIColors.logDebug());
                    break;
                case INFO:
                    setForeground(UIColors.logInfo());
                    break;
                case WARNING:
                    setForeground(UIColors.logWarning());
                    break;
                case ERROR:
                    setForeground(UIColors.logError());
                    break;
                default:
                    setForeground(list.getForeground());
                    break;
            }
        }

        return this;
    }
}