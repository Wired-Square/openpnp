package org.openpnp.gui.support;

import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;

import javax.swing.event.UndoableEditEvent;
import javax.swing.event.UndoableEditListener;
import javax.swing.text.JTextComponent;
import javax.swing.undo.UndoManager;

/**
 * Global handler that provides undo/redo for any JTextComponent.
 * Installed once at startup via {@link #install()}.
 *
 * <ul>
 *   <li>Ctrl+Z: undo</li>
 *   <li>Ctrl+Shift+Z: redo</li>
 * </ul>
 *
 * The edit listener is detached on focus-lost and reattached on
 * focus-gained so that programmatic setText calls (e.g. binding
 * population from readFromObject) are not recorded. User edit
 * history is preserved across focus changes for the session.
 * History is in-memory only and does not persist across restarts.
 */
public class TextFieldUndoHandler implements AWTEventListener {

    private static final String UNDO_MANAGER_KEY = "openpnp.undoManager";
    private static final String UNDO_LISTENER_KEY = "openpnp.undoListener";
    private static final int UNDO_LIMIT = 100;

    public static void install() {
        TextFieldUndoHandler handler = new TextFieldUndoHandler();
        Toolkit.getDefaultToolkit().addAWTEventListener(handler,
                AWTEvent.FOCUS_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
    }

    @Override
    public void eventDispatched(AWTEvent event) {
        if (event instanceof FocusEvent) {
            handleFocusEvent((FocusEvent) event);
        }
        else if (event instanceof KeyEvent) {
            handleKeyEvent((KeyEvent) event);
        }
    }

    private void handleFocusEvent(FocusEvent fe) {
        if (!(fe.getComponent() instanceof JTextComponent)) {
            return;
        }

        JTextComponent tc = (JTextComponent) fe.getComponent();

        if (fe.getID() == FocusEvent.FOCUS_GAINED) {
            onFocusGained(tc);
        }
        else if (fe.getID() == FocusEvent.FOCUS_LOST) {
            onFocusLost(tc);
        }
    }

    private void handleKeyEvent(KeyEvent ke) {
        if (ke.getID() != KeyEvent.KEY_PRESSED) {
            return;
        }
        if (!(ke.getComponent() instanceof JTextComponent)) {
            return;
        }

        JTextComponent tc = (JTextComponent) ke.getComponent();
        UndoManager undoManager = (UndoManager) tc.getClientProperty(UNDO_MANAGER_KEY);
        if (undoManager == null) {
            return;
        }

        boolean isCtrl = (ke.getModifiersEx() & KeyEvent.CTRL_DOWN_MASK) != 0;
        boolean isShift = (ke.getModifiersEx() & KeyEvent.SHIFT_DOWN_MASK) != 0;

        if (isCtrl && !isShift && ke.getKeyCode() == KeyEvent.VK_Z) {
            if (undoManager.canUndo()) {
                undoManager.undo();
            }
            ke.consume();
        }
        else if (isCtrl && isShift && ke.getKeyCode() == KeyEvent.VK_Z) {
            if (undoManager.canRedo()) {
                undoManager.redo();
            }
            ke.consume();
        }
    }

    private void onFocusGained(JTextComponent tc) {
        UndoManager undoManager = (UndoManager) tc.getClientProperty(UNDO_MANAGER_KEY);

        if (undoManager == null) {
            undoManager = new UndoManager();
            undoManager.setLimit(UNDO_LIMIT);
            tc.putClientProperty(UNDO_MANAGER_KEY, undoManager);
        }

        // Attach the listener so user edits are recorded.
        // Skip edits that leave the document empty — these are the
        // intermediate remove step of a setText() (remove-then-insert)
        // and would cause undo to cycle through empty states.
        UndoableEditListener listener = (UndoableEditEvent e) -> {
            if (tc.getDocument().getLength() == 0) {
                return;
            }
            ((UndoManager) tc.getClientProperty(UNDO_MANAGER_KEY)).addEdit(e.getEdit());
        };
        tc.getDocument().addUndoableEditListener(listener);
        tc.putClientProperty(UNDO_LISTENER_KEY, listener);
    }

    private void onFocusLost(JTextComponent tc) {
        // Detach the listener so programmatic changes while unfocused
        // (binding population, setText from model updates) are not recorded.
        UndoableEditListener listener = (UndoableEditListener) tc.getClientProperty(UNDO_LISTENER_KEY);
        if (listener != null) {
            tc.getDocument().removeUndoableEditListener(listener);
            tc.putClientProperty(UNDO_LISTENER_KEY, null);
        }
    }
}
