/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import io.strimzi.api.kafka.model.storage.EphemeralStorageBuilder;
import io.strimzi.api.kafka.model.storage.JbodStorageBuilder;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorageBuilder;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorageOverrideBuilder;
import io.strimzi.api.kafka.model.storage.Storage;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.test.annotations.ParallelSuite;
import io.strimzi.test.annotations.ParallelTest;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@ParallelSuite
public class StorageDiffTest {
    @ParallelTest
    public void testJbodDiff()    {
        Storage jbod = new JbodStorageBuilder().withVolumes(
                new PersistentClaimStorageBuilder().withStorageClass("gp2-ssd").withDeleteClaim(false).withId(0).withSize("100Gi").build(),
                new PersistentClaimStorageBuilder().withStorageClass("gp2-st1").withDeleteClaim(true).withId(1).withSize("1000Gi").build())
                .build();

        Storage jbod2 = new JbodStorageBuilder().withVolumes(
                new PersistentClaimStorageBuilder().withStorageClass("gp2-ssd").withDeleteClaim(false).withId(0).withSize("1000Gi").build(),
                new PersistentClaimStorageBuilder().withStorageClass("gp2-ssd").withDeleteClaim(true).withId(1).withSize("1000Gi").build())
                .build();

        StorageDiff diff = new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, jbod, jbod, 3, 3);
        assertThat(diff.changesType(), is(false));
        assertThat(diff.isEmpty(), is(true));
        assertThat(diff.shrinkSize(), is(false));
        assertThat(diff.isVolumesAddedOrRemoved(), is(false));

        diff = new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, jbod, jbod2, 3, 3);
        assertThat(diff.changesType(), is(false));
        assertThat(diff.isEmpty(), is(false));
        assertThat(diff.shrinkSize(), is(false));
        assertThat(diff.isVolumesAddedOrRemoved(), is(false));
    }

    @ParallelTest
    public void testPersistentDiff()    {
        Storage persistent = new PersistentClaimStorageBuilder().withStorageClass("gp2-ssd").withDeleteClaim(false).withId(0).withSize("100Gi").build();
        Storage persistent2 = new PersistentClaimStorageBuilder().withStorageClass("gp2-st1").withDeleteClaim(false).withId(0).withSize("1000Gi").build();

        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent, persistent, 3, 3).changesType(), is(false));
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent, persistent, 3, 3).isEmpty(), is(true));
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent, persistent, 3, 3).shrinkSize(), is(false));

        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent, persistent2, 3, 3).changesType(), is(false));
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent, persistent2, 3, 3).isEmpty(), is(false));
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent, persistent2, 3, 3).shrinkSize(), is(false));
    }

    @ParallelTest
    public void testPersistentDiffWithOverrides()    {
        Storage persistent = new PersistentClaimStorageBuilder()
                .withStorageClass("gp2-ssd")
                .withDeleteClaim(false)
                .withId(0)
                .withSize("100Gi")
                .build();
        Storage persistent2 = new PersistentClaimStorageBuilder()
                .withStorageClass("gp2-ssd")
                .withDeleteClaim(false)
                .withId(0)
                .withSize("100Gi")
                .withOverrides(new PersistentClaimStorageOverrideBuilder()
                        .withBroker(1)
                        .withStorageClass("gp2-ssd-az1")
                        .build())
                .build();
        Storage persistent3 = new PersistentClaimStorageBuilder()
                .withStorageClass("gp2-ssd")
                .withDeleteClaim(false)
                .withId(0)
                .withSize("100Gi")
                .withOverrides(new PersistentClaimStorageOverrideBuilder()
                        .withBroker(1)
                        .withStorageClass("gp2-ssd-az2")
                        .build())
                .build();

        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent, persistent, 3, 3).changesType(), is(false));
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent, persistent, 3, 3).isEmpty(), is(true));
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent, persistent, 3, 3).shrinkSize(), is(false));

        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent, persistent2, 3, 3).changesType(), is(false));
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent, persistent2, 3, 3).isEmpty(), is(false));
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent, persistent2, 3, 3).shrinkSize(), is(false));

        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent2, persistent3, 3, 3).changesType(), is(false));
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent2, persistent3, 3, 3).isEmpty(), is(false));
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent2, persistent3, 3, 3).shrinkSize(), is(false));
    }

    @ParallelTest
    public void testSizeChanges()    {
        Storage persistent = new PersistentClaimStorageBuilder().withStorageClass("gp2-ssd").withDeleteClaim(false).withId(0).withSize("100Gi").build();
        Storage persistent2 = new PersistentClaimStorageBuilder().withStorageClass("gp2-ssd").withDeleteClaim(false).withId(0).withSize("1000Gi").build();
        Storage persistent3 = new PersistentClaimStorageBuilder().withStorageClass("gp2-ssd").withDeleteClaim(false).withId(0).withSize("10Gi").build();

        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent, persistent, 3, 3).shrinkSize(), is(false));
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent, persistent2, 3, 3).shrinkSize(), is(false));
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent, persistent3, 3, 3).shrinkSize(), is(true));
    }

    @ParallelTest
    public void testEphemeralDiff()    {
        Storage ephemeral = new EphemeralStorageBuilder().build();

        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, ephemeral, ephemeral, 3, 3).changesType(), is(false));
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, ephemeral, ephemeral, 3, 3).isEmpty(), is(true));
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, ephemeral, ephemeral, 3, 3).shrinkSize(), is(false));
    }

    @ParallelTest
    public void testCrossDiff()    {
        Storage jbod = new JbodStorageBuilder().withVolumes(
                new PersistentClaimStorageBuilder().withStorageClass("gp2-ssd").withDeleteClaim(false).withId(0).withSize("100Gi").build(),
                new PersistentClaimStorageBuilder().withStorageClass("gp2-st1").withDeleteClaim(true).withId(1).withSize("1000Gi").build())
                .build();

        Storage ephemeral = new EphemeralStorageBuilder().build();

        Storage persistent = new PersistentClaimStorageBuilder().withStorageClass("gp2-ssd").withDeleteClaim(false).withId(0).withSize("100Gi").build();

        StorageDiff diffJbodEphemeral = new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, jbod, ephemeral, 3, 3);
        StorageDiff diffPersistentEphemeral = new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent, ephemeral, 3, 3);
        StorageDiff diffJbodPersistent = new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, jbod, persistent, 3, 3);

        assertThat(diffJbodEphemeral.changesType(), is(true));
        assertThat(diffPersistentEphemeral.changesType(), is(true));
        assertThat(diffJbodPersistent.changesType(), is(true));

        assertThat(diffJbodEphemeral.isEmpty(), is(false));
        assertThat(diffPersistentEphemeral.isEmpty(), is(false));
        assertThat(diffJbodPersistent.isEmpty(), is(false));

        assertThat(diffJbodEphemeral.isVolumesAddedOrRemoved(), is(false));
        assertThat(diffPersistentEphemeral.isVolumesAddedOrRemoved(), is(false));
        assertThat(diffJbodPersistent.isVolumesAddedOrRemoved(), is(false));
    }

    @ParallelTest
    public void testJbodDiffWithNewVolume()    {
        Storage jbod = new JbodStorageBuilder()
                .withVolumes(new PersistentClaimStorageBuilder().withStorageClass("gp2-ssd").withDeleteClaim(false).withId(0).withSize("1000Gi").build())
                .build();

        Storage jbod2 = new JbodStorageBuilder().withVolumes(
                new PersistentClaimStorageBuilder().withStorageClass("gp2-ssd").withDeleteClaim(false).withId(0).withSize("1000Gi").build(),
                new PersistentClaimStorageBuilder().withStorageClass("gp2-ssd").withDeleteClaim(true).withId(1).withSize("1000Gi").build())
                .build();

        Storage jbod3 = new JbodStorageBuilder().withVolumes(
                new PersistentClaimStorageBuilder().withStorageClass("gp2-st1").withDeleteClaim(false).withId(0).withSize("100Gi").build(),
                new PersistentClaimStorageBuilder().withStorageClass("gp2-ssd").withDeleteClaim(true).withId(1).withSize("1000Gi").build())
                .build();

        Storage jbod4 = new JbodStorageBuilder()
                .withVolumes(new PersistentClaimStorageBuilder().withStorageClass("gp2-ssd").withDeleteClaim(false).withId(2).withSize("1000Gi").build())
                .build();

        Storage jbod5 = new JbodStorageBuilder().withVolumes(
                new PersistentClaimStorageBuilder().withStorageClass("gp2-ssd").withDeleteClaim(true).withId(1).withSize("1000Gi").build())
                .build();

        Storage jbod6 = new JbodStorageBuilder().withVolumes(
                new PersistentClaimStorageBuilder().withStorageClass("gp2-ssd").withDeleteClaim(false).withId(0).withSize("1000Gi").build(),
                new PersistentClaimStorageBuilder().withStorageClass("gp2-st1").withDeleteClaim(true).withId(2).withSize("5000Gi").build())
                .build();

        // Volume added
        StorageDiff diff = new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, jbod, jbod2, 3, 3);
        assertThat(diff.changesType(), is(false));
        assertThat(diff.isEmpty(), is(true));
        assertThat(diff.shrinkSize(), is(false));
        assertThat(diff.isVolumesAddedOrRemoved(), is(true));

        // Volume removed
        diff = new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, jbod2, jbod, 3, 3);
        assertThat(diff.changesType(), is(false));
        assertThat(diff.isEmpty(), is(true));
        assertThat(diff.shrinkSize(), is(false));
        assertThat(diff.isVolumesAddedOrRemoved(), is(true));

        // Volume added with changes
        diff = new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, jbod, jbod3, 3, 3);
        assertThat(diff.changesType(), is(false));
        assertThat(diff.isEmpty(), is(false));
        assertThat(diff.shrinkSize(), is(true));
        assertThat(diff.isVolumesAddedOrRemoved(), is(true));

        // No volume added, but with changes
        diff = new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, jbod2, jbod3, 3, 3);
        assertThat(diff.changesType(), is(false));
        assertThat(diff.isEmpty(), is(false));
        assertThat(diff.shrinkSize(), is(true));
        assertThat(diff.isVolumesAddedOrRemoved(), is(false));

        // Volume removed from the beginning
        diff = new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, jbod3, jbod5, 3, 3);
        assertThat(diff.changesType(), is(false));
        assertThat(diff.isEmpty(), is(true));
        assertThat(diff.shrinkSize(), is(false));
        assertThat(diff.isVolumesAddedOrRemoved(), is(true));

        // Volume added to the beginning
        diff = new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, jbod5, jbod3, 3, 3);
        assertThat(diff.changesType(), is(false));
        assertThat(diff.isEmpty(), is(true));
        assertThat(diff.shrinkSize(), is(false));
        assertThat(diff.isVolumesAddedOrRemoved(), is(true));

        // Volume replaced with another ID and another volume which is kept changed
        diff = new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, jbod3, jbod6, 3, 3);
        assertThat(diff.changesType(), is(false));
        assertThat(diff.isEmpty(), is(false));
        assertThat(diff.shrinkSize(), is(false));
        assertThat(diff.isVolumesAddedOrRemoved(), is(true));

        // Volume replaced with another ID in single volume broker
        diff = new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, jbod, jbod4, 3, 3);
        assertThat(diff.changesType(), is(false));
        assertThat(diff.isEmpty(), is(true));
        assertThat(diff.shrinkSize(), is(false));
        assertThat(diff.isVolumesAddedOrRemoved(), is(true));

        // Volume replaced with another ID without chenging the volumes which are kept
        diff = new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, jbod2, jbod6, 3, 3);
        assertThat(diff.changesType(), is(false));
        assertThat(diff.isEmpty(), is(true));
        assertThat(diff.shrinkSize(), is(false));
        assertThat(diff.isVolumesAddedOrRemoved(), is(true));
    }

    @ParallelTest
    public void testSizeChangesInJbod()    {
        Storage jbod = new JbodStorageBuilder().withVolumes(
                new PersistentClaimStorageBuilder().withStorageClass("gp2-ssd").withDeleteClaim(false).withId(0).withSize("1000Gi").build(),
                new PersistentClaimStorageBuilder().withStorageClass("gp2-ssd").withDeleteClaim(true).withId(1).withSize("1000Gi").build())
                .build();

        Storage jbod2 = new JbodStorageBuilder().withVolumes(
                new PersistentClaimStorageBuilder().withStorageClass("gp2-ssd").withDeleteClaim(false).withId(0).withSize("5000Gi").build(),
                new PersistentClaimStorageBuilder().withStorageClass("gp2-ssd").withDeleteClaim(true).withId(1).withSize("1000Gi").build())
                .build();

        Storage jbod3 = new JbodStorageBuilder().withVolumes(
                new PersistentClaimStorageBuilder().withStorageClass("gp2-ssd").withDeleteClaim(false).withId(0).withSize("1000Gi").build(),
                new PersistentClaimStorageBuilder().withStorageClass("gp2-ssd").withDeleteClaim(true).withId(1).withSize("500Gi").build())
                .build();

        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, jbod, jbod, 3, 3).shrinkSize(), is(false));
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, jbod, jbod2, 3, 3).shrinkSize(), is(false));
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, jbod, jbod3, 3, 3).shrinkSize(), is(true));
    }

    @ParallelTest
    public void testPersistentDiffWithOverridesChangesToExistingOverrides()    {
        Storage persistent = new PersistentClaimStorageBuilder()
                .withStorageClass("gp2-ssd")
                .withDeleteClaim(false)
                .withId(0)
                .withSize("100Gi")
                .withOverrides(
                        new PersistentClaimStorageOverrideBuilder()
                                .withBroker(0)
                                .withStorageClass("gp2-ssd-az1")
                                .build(),
                        new PersistentClaimStorageOverrideBuilder()
                                .withBroker(1)
                                .withStorageClass("gp2-ssd-az2")
                                .build())
                .build();

        Storage persistent2 = new PersistentClaimStorageBuilder()
                .withStorageClass("gp2-ssd")
                .withDeleteClaim(false)
                .withId(0)
                .withSize("100Gi")
                .withOverrides(
                        new PersistentClaimStorageOverrideBuilder()
                                .withBroker(0)
                                .withStorageClass("gp2-ssd-az1")
                                .build(),
                        new PersistentClaimStorageOverrideBuilder()
                                .withBroker(1)
                                .withStorageClass("new-sc")
                                .build())
                .build();

        // Test no changes when the diff is the same
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent, persistent, 2, 2).isEmpty(), is(true));
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent2, persistent2, 2, 2).isEmpty(), is(true));

        // Override changed for node which does not exist => is allowed
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent, persistent2, 1, 1).isEmpty(), is(true));

        // Override changed for node which is being scaled up => is allowed
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent, persistent2, 1, 2).isEmpty(), is(true));

        // Override changed for existing node  => is not allowed
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent, persistent2, 2, 2).isEmpty(), is(false));

        // Override changed for node being scaled down => is allowed
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent, persistent2, 2, 1).isEmpty(), is(true));
    }

    @ParallelTest
    public void testPersistentDiffWithOverridesBeingAdded()    {
        Storage persistent = new PersistentClaimStorageBuilder()
                .withStorageClass("gp2-ssd")
                .withDeleteClaim(false)
                .withId(0)
                .withSize("100Gi")
                .build();

        Storage persistent2 = new PersistentClaimStorageBuilder()
                .withStorageClass("gp2-ssd")
                .withDeleteClaim(false)
                .withId(0)
                .withSize("100Gi")
                .withOverrides(
                        new PersistentClaimStorageOverrideBuilder()
                                .withBroker(0)
                                .withStorageClass("gp2-ssd-az1")
                                .build())
                .build();

        Storage persistent3 = new PersistentClaimStorageBuilder()
                .withStorageClass("gp2-ssd")
                .withDeleteClaim(false)
                .withId(0)
                .withSize("100Gi")
                .withOverrides(
                        new PersistentClaimStorageOverrideBuilder()
                                .withBroker(0)
                                .withStorageClass("gp2-ssd-az1")
                                .build(),
                        new PersistentClaimStorageOverrideBuilder()
                                .withBroker(1)
                                .withStorageClass("gp2-ssd-az2")
                                .build())
                .build();

        // Test no changes
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent, persistent, 2, 2).isEmpty(), is(true));
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent2, persistent2, 2, 2).isEmpty(), is(true));
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent3, persistent3, 2, 2).isEmpty(), is(true));

        // Overrides added for existing nodes => not allowed
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent, persistent2, 2, 2).isEmpty(), is(false));
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent, persistent3, 2, 2).isEmpty(), is(false));
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent2, persistent3, 2, 2).isEmpty(), is(false));

        // Overrides added for new nodes => allowed
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent2, persistent3, 1, 2).isEmpty(), is(true));

        // Overrides added for removed nodes => allowed
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent2, persistent3, 2, 1).isEmpty(), is(true));

        // Overrides added for non-existing nodes => allowed
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent2, persistent3, 1, 1).isEmpty(), is(true));
    }

    @ParallelTest
    public void testPersistentDiffWithOverridesBeingRemoved()    {
        Storage persistent = new PersistentClaimStorageBuilder()
                .withStorageClass("gp2-ssd")
                .withDeleteClaim(false)
                .withId(0)
                .withSize("100Gi")
                .build();

        Storage persistent2 = new PersistentClaimStorageBuilder()
                .withStorageClass("gp2-ssd")
                .withDeleteClaim(false)
                .withId(0)
                .withSize("100Gi")
                .withOverrides(
                        new PersistentClaimStorageOverrideBuilder()
                                .withBroker(0)
                                .withStorageClass("gp2-ssd-az1")
                                .build())
                .build();

        Storage persistent3 = new PersistentClaimStorageBuilder()
                .withStorageClass("gp2-ssd")
                .withDeleteClaim(false)
                .withId(0)
                .withSize("100Gi")
                .withOverrides(
                        new PersistentClaimStorageOverrideBuilder()
                                .withBroker(0)
                                .withStorageClass("gp2-ssd-az1")
                                .build(),
                        new PersistentClaimStorageOverrideBuilder()
                                .withBroker(1)
                                .withStorageClass("gp2-ssd-az2")
                                .build())
                .build();

        // Test no changes
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent, persistent, 2, 2).isEmpty(), is(true));
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent2, persistent2, 2, 2).isEmpty(), is(true));
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent3, persistent3, 2, 2).isEmpty(), is(true));

        // Overrides removed for existing nodes => not allowed
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent3, persistent, 2, 2).isEmpty(), is(false));
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent3, persistent2, 2, 2).isEmpty(), is(false));
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent2, persistent, 2, 2).isEmpty(), is(false));

        // Overrides removed for new nodes => allowed
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent3, persistent2, 1, 2).isEmpty(), is(true));

        // Overrides removed for removed nodes => allowed
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent3, persistent2, 2, 1).isEmpty(), is(true));

        // Overrides removed for non-existing nodes => allowed
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent3, persistent2, 1, 1).isEmpty(), is(true));
    }

    @ParallelTest
    public void testPersistentDiffWithOverridesBeingAddedAndRemoved()    {
        Storage persistent = new PersistentClaimStorageBuilder()
                .withStorageClass("gp2-ssd")
                .withDeleteClaim(false)
                .withId(0)
                .withSize("100Gi")
                .withOverrides(
                        new PersistentClaimStorageOverrideBuilder()
                                .withBroker(0)
                                .withStorageClass("gp2-ssd-az1")
                                .build())
                .build();

        Storage persistent2 = new PersistentClaimStorageBuilder()
                .withStorageClass("gp2-ssd")
                .withDeleteClaim(false)
                .withId(0)
                .withSize("100Gi")
                .withOverrides(
                        new PersistentClaimStorageOverrideBuilder()
                                .withBroker(1)
                                .withStorageClass("gp2-ssd-az2")
                                .build())
                .build();

        // Test no changes
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent, persistent, 2, 2).isEmpty(), is(true));
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent2, persistent2, 2, 2).isEmpty(), is(true));

        // Overrides added and removed for existing nodes => not allowed
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent2, persistent, 2, 2).isEmpty(), is(false));
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent, persistent2, 2, 2).isEmpty(), is(false));

        // Overrides added for new nodes but removed for old => not allowed
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent, persistent2, 1, 2).isEmpty(), is(false));

        // Overrides removed for new nodes but added for old => not allowed
        assertThat(new StorageDiff(Reconciliation.DUMMY_RECONCILIATION, persistent2, persistent, 1, 2).isEmpty(), is(false));
    }
}
