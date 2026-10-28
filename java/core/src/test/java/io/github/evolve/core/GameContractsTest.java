package io.github.evolve.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GameContractsTest {
    @Test
    void guiCannotModifySessionDataThroughSnapshots() {
        var costs = new LinkedHashMap<>(Map.of("RNA", 2d));
        var tooltips = new ArrayList<>(List.of(TextRef.of("evo_dna_desc")));
        var action = new GameSnapshot.ActionView("evolution-dna", "evolution", TextRef.of("evo_dna_title"),
                tooltips, 0, costs, true, true);
        costs.clear();
        tooltips.clear();
        assertEquals(2d, action.costs().get("RNA"));
        assertEquals(1, action.tooltip().size());
        assertThrows(UnsupportedOperationException.class, () -> action.costs().clear());
        assertThrows(UnsupportedOperationException.class, () -> action.tooltip().clear());
    }

    @Test
    void packagedSpeciesAndGeneraAreConsistent() {
        assertEquals(57, SpeciesCatalog.all().size());
        for (var species : SpeciesCatalog.all().values()) {
            assertTrue(SpeciesCatalog.genusOrder().contains(species.genus()), species.id());
            assertDoesNotThrow(() -> SpeciesCatalog.startingTraits(species.id()));
        }
        assertEquals(1d, SpeciesCatalog.startingTraits("human").get("adaptable"));
        assertEquals(1d, SpeciesCatalog.startingTraits("human").get("creative"));
        assertThrows(UnsupportedOperationException.class, () -> SpeciesCatalog.all().clear());
    }
}
