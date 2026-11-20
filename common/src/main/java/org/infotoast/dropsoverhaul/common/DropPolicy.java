package org.infotoast.dropsoverhaul.common;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Immutable snapshot of the existing configuration; contains no loader APIs. */
public final class DropPolicy {
    private final Set<String> selectedBlocks;
    private final boolean deleteOverflow;

    public DropPolicy(Collection<String> selectedBlocks, boolean deleteOverflow) {
        this.selectedBlocks = Collections.unmodifiableSet(new HashSet<String>(selectedBlocks));
        this.deleteOverflow = deleteOverflow;
    }

    public boolean selects(String block) {
        return selectedBlocks.contains(block);
    }

    public <T> void transfer(Collection<T> nativeDrops, Inventory<T> inventory, Overflow<T> overflow) {
        for (T stack : nativeDrops) {
            Collection<T> remaining = inventory.insert(stack);
            if (!deleteOverflow) {
                for (T leftover : remaining) {
                    overflow.drop(leftover);
                }
            }
        }
    }

    public interface Inventory<T> {
        Collection<T> insert(T stack);
    }

    public interface Overflow<T> {
        void drop(T stack);
    }
}
