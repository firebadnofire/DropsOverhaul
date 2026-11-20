package org.infotoast.dropsoverhaul.common;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DropPolicyTest {
    @Test
    void configurationIsAnExactImmutableSnapshot() {
        List<String> names = new ArrayList<>(Arrays.asList("STONE"));
        DropPolicy policy = new DropPolicy(names, false);
        names.clear();
        assertTrue(policy.selects("STONE"));
        assertFalse(policy.selects("stone"));
        assertFalse(policy.selects("DIAMOND_ORE"));
    }

    @Test
    void nativeQuantitiesAreConservedWhenOverflowIsRetained() {
        List<Integer> overflow = new ArrayList<>();
        int[] inserted = {0};
        new DropPolicy(Collections.emptyList(), false).transfer(Arrays.asList(7, 4), amount -> {
            int accepted = Math.min(amount, 9 - inserted[0]);
            inserted[0] += accepted;
            return amount == accepted ? Collections.emptyList() : Collections.singletonList(amount - accepted);
        }, overflow::add);
        assertEquals(9, inserted[0]);
        assertEquals(Arrays.asList(2), overflow);
    }

    @Test
    void onlyOverflowIsDeletedWhenConfigured() {
        List<Integer> attempted = new ArrayList<>();
        new DropPolicy(Collections.emptyList(), true).transfer(Arrays.asList(7, 4), amount -> {
            attempted.add(amount);
            return Collections.singletonList(amount);
        }, amount -> fail("Overflow must be deleted"));
        assertEquals(Arrays.asList(7, 4), attempted);
    }
}
