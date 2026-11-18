package com.caseware.pendingupdates.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Version semantics: accumulation, declines, withdrawals, market branches (Part 1, section 2).
 */
class VersionGraphTest {
    VersionGraph g;

    void add(String id, String branch, String parent) {
        g.add(new VersionGraph.Version(id, branch, parent, false));
    }

    @BeforeEach
    void lineage() {
        g = new VersionGraph();
        add("v3", "us", null);
        add("v4", "us", "v3");
        add("v5", "us", "v4");
        add("v6", "us", "v5");
        add("v7", "us", "v6");
    }

    @Test
    void accumulatedUpdatesProduceOneTargetFromTheBase() {
        assertEquals(Optional.of("v7"), g.resolvePendingTarget("us", "v3", null));
    }

    @Test
    void appliedNewestHasNothingPending() {
        assertEquals(Optional.empty(), g.resolvePendingTarget("us", "v7", null));
    }

    @Test
    void declinedV5IsOfferedAgainAsV7() {
        assertEquals(Optional.of("v7"), g.resolvePendingTarget("us", "v3", "v5"));
    }

    @Test
    void declineOfTheNewestPinsUntilSomethingNewerArrives() {
        assertEquals(Optional.empty(), g.resolvePendingTarget("us", "v3", "v7"));
        add("v8", "us", "v7");
        assertEquals(Optional.of("v8"), g.resolvePendingTarget("us", "v3", "v7"));
    }

    @Test
    void withdrawnTipFallsBackToPreviousVersion() {
        g.withdraw("v7");
        assertEquals(Optional.of("v6"), g.resolvePendingTarget("us", "v3", null));
    }

    @Test
    void withdrawalIsNotInheritedByDescendants() {
        g.withdraw("v7");
        add("v8", "us", "v7");
        assertEquals(Optional.of("v8"), g.resolvePendingTarget("us", "v3", null));
    }

    @Test
    void withdrawalBeforePublishIsRemembered() {
        g.withdraw("v8");
        add("v8", "us", "v7");
        assertEquals(Optional.of("v7"), g.resolvePendingTarget("us", "v3", null));
    }

    @Test
    void marketBranchesAreIndependent() {
        add("v5-ca", "ca", "v5");
        add("v6-ca", "ca", "v5-ca");
        assertEquals(Optional.of("v6-ca"), g.resolvePendingTarget("ca", "v4", null));
        assertEquals(Optional.of("v7"), g.resolvePendingTarget("us", "v4", null));
    }

    @Test
    void ambiguousForkOnTheSameBranchIsNotGuessed() {
        add("v7b", "us", "v6");
        assertEquals(Optional.empty(), g.resolvePendingTarget("us", "v3", null));
    }

    @Test
    void unknownAppliedVersionHasNoPath() {
        assertEquals(Optional.empty(), g.resolvePendingTarget("us", "v1", null));
    }

    @Test
    void redeliveredPublishDoesNotResurrectWithdrawnVersion() {
        g.withdraw("v7");
        add("v7", "us", "v6");
        assertEquals(Optional.of("v6"), g.resolvePendingTarget("us", "v3", null));
    }
}
