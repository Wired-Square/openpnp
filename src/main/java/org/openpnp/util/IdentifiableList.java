package org.openpnp.util;

import java.util.concurrent.CopyOnWriteArrayList;

import org.openpnp.model.Identifiable;

/**
 * A List specifically for storing implementations of Identifiable. This class adds a get(String)
 * method for getting the Identifiable object with the specified id from the list. Currently
 * performs a simple search through the list but is intended to be indexed eventually.
 * 
 * TODO: Perform indexing on insert and remove so that get(String) can perform better. Consider what
 * happens if an id changes out from under us.
 * 
 * Copy-on-write, because these lists (machine heads, cameras, feeders, board placements and so on)
 * are edited on the EDT while the machine thread iterates them: each change replaces the backing
 * array, and an iteration sees the list as it was when it started. Iterators do not support
 * remove(); use remove(Object) or removeIf.
 * 
 * @param <E>
 */
public class IdentifiableList<E extends Identifiable> extends CopyOnWriteArrayList<E> {
    private static final long serialVersionUID = -2350184908321182804L;
    
    public IdentifiableList() {
        super();
    }
    
    public IdentifiableList(IdentifiableList<E> identifiableList) {
        super(identifiableList);
    }
    
    public String createId(String prefix) {
        //Only software people start counting at zero! Everyone else starts counting at one.
        for (int i = 1; ; i++) {
            if (get(prefix + i) == null) {
                return prefix + i;
            }
        }
    }

    public E get(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (E e : this) {
            if (e.getId().equals(id)) {
                return e;
            }
        }
        return null;
    }
}
