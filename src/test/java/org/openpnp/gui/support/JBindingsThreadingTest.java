package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.junit.jupiter.api.Test;
import org.openpnp.model.AbstractModelObject;

public class JBindingsThreadingTest {
    public static class Counter extends AbstractModelObject {
        private String count = "0"; //$NON-NLS-1$

        public String getCount() {
            return count;
        }

        public void setCount(String count) {
            String oldValue = this.count;
            this.count = count;
            firePropertyChange("count", oldValue, count); //$NON-NLS-1$
        }
    }

    private static final class OffEdtRecorder implements DocumentListener {
        final AtomicBoolean changedOffEdt = new AtomicBoolean();

        private void record() {
            if (!SwingUtilities.isEventDispatchThread()) {
                changedOffEdt.set(true);
            }
        }

        @Override
        public void insertUpdate(DocumentEvent e) {
            record();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            record();
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
            record();
        }
    }

    @Test
    public void aChangeOnAnotherThreadReachesTheComponentOnTheEdt() throws Exception {
        Counter counter = new Counter();
        JTextField field = new JTextField();
        SwingUtilities.invokeAndWait(() -> JBindings.bind(counter, "count", field, "text")); //$NON-NLS-1$ //$NON-NLS-2$
        OffEdtRecorder recorder = new OffEdtRecorder();
        field.getDocument().addDocumentListener(recorder);

        Thread machineThread = new Thread(() -> counter.setCount("7")); //$NON-NLS-1$
        machineThread.start();
        machineThread.join();
        SwingUtilities.invokeAndWait(() -> {});

        assertFalse(recorder.changedOffEdt.get());
        assertEquals("7", field.getText()); //$NON-NLS-1$
    }

    @Test
    public void aChangeOnTheEdtIsAppliedImmediately() throws Exception {
        Counter counter = new Counter();
        JTextField field = new JTextField();
        String[] textAfterSet = new String[1];

        SwingUtilities.invokeAndWait(() -> {
            JBindings.bind(counter, "count", field, "text"); //$NON-NLS-1$ //$NON-NLS-2$
            counter.setCount("3"); //$NON-NLS-1$
            textAfterSet[0] = field.getText();
        });

        assertEquals("3", textAfterSet[0]); //$NON-NLS-1$
    }
}
