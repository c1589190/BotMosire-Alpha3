package io.mosire.bma3.app.channel.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.bma3.core.message.Direction;
import io.mosire.bma3.core.message.Segment;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CrossChannelMatcherTest {

    private static final String THREAD = "thread.alpha";
    private static final String ONEBOT_ACCOUNT = "bot-1";
    private static final String OFFICIAL_ACCOUNT = "app-1";
    private static final String ONEBOT_SOURCE = "qq_group/bot-1/955432692";
    private static final String OFFICIAL_SOURCE = "qq_official_group/app-1/group_openid_A";
    private static final Instant BASE = Instant.parse("2026-11-17T00:00:00Z");
    private static final Duration FIVE_SECONDS = Duration.ofSeconds(5);

    private final CrossChannelMatcher matcher = new CrossChannelMatcher();

    @Test
    void exactTextWithExplicitSameIdentityIsMatchedAndCounted() {
        MatchResult result = match(MatchingMode.STRICT, FIVE_SECONDS,
                List.of(identity(Channel.ONEBOT, "123", "canonical-a"),
                        identity(Channel.QQ_OFFICIAL, "openid-A", "canonical-a")),
                obs("o-onebot", Channel.ONEBOT, "123", BASE, "你好  世界"),
                obs("o-official", Channel.QQ_OFFICIAL, "openid-A", BASE.plusSeconds(1), "你好 世界"));

        assertThat(result.counts().observations()).isEqualTo(2);
        assertThat(result.counts().matchedObservations()).isEqualTo(2);
        assertThat(result.counts().matchedPairs()).isEqualTo(1);
        assertThat(result.counts().unverifiedObservations()).isZero();
        assertThat(result.counts().channelOnlyObservations()).isZero();
        assertThat(result.pairs()).singleElement().satisfies(pair -> {
            assertThat(pair.state()).isEqualTo(ObservationState.MATCHED);
            assertThat(pair.first().channel()).isEqualTo("onebot");
            assertThat(pair.second().channel()).isEqualTo("qq_official");
        });
        assertThat(result.observations()).allSatisfy(item -> {
            assertThat(item.state()).isEqualTo(ObservationState.MATCHED);
            assertThat(item.peer()).isPresent();
            assertThat(item.observation().rawSource()).isIn(ONEBOT_SOURCE, OFFICIAL_SOURCE);
            assertThat(item.observation().accountId()).isIn(ONEBOT_ACCOUNT, OFFICIAL_ACCOUNT);
        });
        assertThat(Arrays.stream(ObservationRef.class.getRecordComponents())
                .map(RecordComponent::getName)).doesNotContain("contentHash");
        assertThat(Arrays.stream(MatchResult.class.getRecordComponents())
                .map(RecordComponent::getName)).doesNotContain("resultHash");
    }

    @Test
    void strictModeRequiresExplicitMappingAndNeverUsesRawSenderEquality() {
        MatchResult result = match(MatchingMode.STRICT, FIVE_SECONDS, List.of(),
                obs("onebot", Channel.ONEBOT, "same-raw-id", BASE, "hello"),
                obs("official", Channel.QQ_OFFICIAL, "same-raw-id", BASE, "hello"));

        assertThat(result.counts().matchedObservations()).isZero();
        assertThat(result.counts().unverifiedObservations()).isEqualTo(2);
        assertThat(result.pairs()).isEmpty();
        assertThat(result.observations()).extracting(ObservationResult::state)
                .containsOnly(ObservationState.UNVERIFIED);
    }

    @Test
    void oneSidedMappingIsStillUnverifiedInStrictMode() {
        MatchResult result = match(MatchingMode.STRICT, FIVE_SECONDS,
                List.of(identity(Channel.ONEBOT, "123", "canonical-a")),
                obs("onebot", Channel.ONEBOT, "123", BASE, "hello"),
                obs("official", Channel.QQ_OFFICIAL, "openid-A", BASE, "hello"));

        assertThat(result.counts().unverifiedObservations()).isEqualTo(2);
        assertThat(result.pairs()).isEmpty();
    }

    @Test
    void explicitlyDifferentIdentitiesDoNotMatchOrPretendToBeUnverified() {
        MatchResult result = match(MatchingMode.STRICT, FIVE_SECONDS,
                List.of(identity(Channel.ONEBOT, "123", "canonical-a"),
                        identity(Channel.QQ_OFFICIAL, "openid-A", "canonical-b")),
                obs("onebot", Channel.ONEBOT, "123", BASE, "hello"),
                obs("official", Channel.QQ_OFFICIAL, "openid-A", BASE, "hello"));

        assertThat(result.counts().matchedObservations()).isZero();
        assertThat(result.counts().unverifiedObservations()).isZero();
        assertThat(result.counts().channelOnlyObservations()).isEqualTo(2);
        assertThat(result.pairs()).isEmpty();
    }

    @Test
    void timeToleranceIsInclusiveBoundedAndOutsideToleranceDoesNotPair() {
        List<IdentityMapping> identities = List.of(
                identity(Channel.ONEBOT, "123", "canonical-a"),
                identity(Channel.QQ_OFFICIAL, "openid-A", "canonical-a"));

        MatchResult boundary = match(MatchingMode.STRICT, FIVE_SECONDS, identities,
                obs("onebot", Channel.ONEBOT, "123", BASE, "hello"),
                obs("official", Channel.QQ_OFFICIAL, "openid-A", BASE.plusSeconds(5), "hello"));
        assertThat(boundary.counts().matchedPairs()).isEqualTo(1);

        MatchResult outside = match(MatchingMode.STRICT, FIVE_SECONDS, identities,
                obs("onebot", Channel.ONEBOT, "123", BASE, "hello"),
                obs("official", Channel.QQ_OFFICIAL, "openid-A",
                        BASE.plusSeconds(5).plusNanos(1), "hello"));
        assertThat(outside.counts().matchedPairs()).isZero();
        assertThat(outside.counts().channelOnlyObservations()).isEqualTo(2);
    }

    @Test
    void sameTextDuplicateInWindowMakesEveryCandidateUnverified() {
        MatchResult result = match(MatchingMode.STRICT, FIVE_SECONDS,
                List.of(identity(Channel.ONEBOT, "123", "canonical-a"),
                        identity(Channel.QQ_OFFICIAL, "openid-A", "canonical-a"),
                        identity(Channel.QQ_OFFICIAL, "openid-B", "canonical-a")),
                obs("onebot", Channel.ONEBOT, "123", BASE, "same"),
                obs("official-a", Channel.QQ_OFFICIAL, "openid-A", BASE, "same"),
                obs("official-b", Channel.QQ_OFFICIAL, "openid-B", BASE, "same"));

        assertThat(result.counts().matchedObservations()).isZero();
        assertThat(result.counts().unverifiedObservations()).isEqualTo(3);
        assertThat(result.pairs()).isEmpty();
    }

    @Test
    void anUnresolvedExtraPeerBlocksAnOtherwiseUniqueMatch() {
        MatchResult result = match(MatchingMode.STRICT, FIVE_SECONDS,
                List.of(identity(Channel.ONEBOT, "123", "canonical-a"),
                        identity(Channel.QQ_OFFICIAL, "openid-A", "canonical-a")),
                obs("onebot", Channel.ONEBOT, "123", BASE, "same"),
                obs("official-mapped", Channel.QQ_OFFICIAL, "openid-A", BASE, "same"),
                obs("official-unmapped", Channel.QQ_OFFICIAL, "openid-B", BASE, "same"));

        assertThat(result.counts().matchedObservations()).isZero();
        assertThat(result.counts().unverifiedObservations()).isEqualTo(3);
        assertThat(result.pairs()).isEmpty();
    }

    @Test
    void sourceScopedIdentityOnlyAppliesToThatExactRawSource() {
        IdentityMapping onebotSourceScope = new IdentityMapping(THREAD, Channel.ONEBOT, "123",
                "canonical-a", IdentityMapping.ScopeType.SOURCE, ONEBOT_SOURCE);
        IdentityMapping officialWrongSourceScope = new IdentityMapping(THREAD,
                Channel.QQ_OFFICIAL, "openid-A", "canonical-a",
                IdentityMapping.ScopeType.SOURCE,
                "qq_official_group/app-1/another_group");

        MatchResult mismatchedScope = match(MatchingMode.STRICT, FIVE_SECONDS,
                List.of(onebotSourceScope, officialWrongSourceScope),
                obs("onebot", Channel.ONEBOT, "123", BASE, "hello"),
                obs("official", Channel.QQ_OFFICIAL, "openid-A", BASE, "hello"));
        assertThat(mismatchedScope.counts().unverifiedObservations()).isEqualTo(2);
        assertThat(mismatchedScope.pairs()).isEmpty();

        IdentityMapping officialCorrectScope = new IdentityMapping(THREAD,
                Channel.QQ_OFFICIAL, "openid-A", "canonical-a",
                IdentityMapping.ScopeType.SOURCE, OFFICIAL_SOURCE);
        MatchResult matched = match(MatchingMode.STRICT, FIVE_SECONDS,
                List.of(onebotSourceScope, officialCorrectScope),
                obs("onebot", Channel.ONEBOT, "123", BASE, "hello"),
                obs("official", Channel.QQ_OFFICIAL, "openid-A", BASE, "hello"));
        assertThat(matched.counts().matchedPairs()).isEqualTo(1);
    }

    @Test
    void observationMustMatchActiveBindingChannelAccountAndSource() {
        MatchObservation wrongAccount = obsWithFlags("onebot", Channel.ONEBOT, "123", BASE,
                ContentIntegrity.COMPLETE, TimeReliability.RELIABLE, true,
                new Segment("text", "hello"), "other-account", ONEBOT_SOURCE, "msg-onebot");
        MatchObservation official = obs("official", Channel.QQ_OFFICIAL, "openid-A", BASE, "hello");

        assertThatThrownBy(() -> new MatchInput(THREAD, MatchingMode.STRICT, FIVE_SECONDS,
                bindings(), List.of(), List.of(wrongAccount, official)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel/account/rawSource");

        MatchObservation wrongSource = obsWithFlags("onebot-source", Channel.ONEBOT, "123", BASE,
                ContentIntegrity.COMPLETE, TimeReliability.RELIABLE, true,
                new Segment("text", "hello"), ONEBOT_ACCOUNT, "qq_group/bot-1/other",
                "msg-onebot-source");
        assertThatThrownBy(() -> new MatchInput(THREAD, MatchingMode.STRICT, FIVE_SECONDS,
                bindings(), List.of(), List.of(wrongSource, official)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel/account/rawSource");
    }

    @Test
    void conflictingExplicitMappingsForOneSenderAreAmbiguousAndFailClosed() {
        MatchResult result = match(MatchingMode.STRICT, FIVE_SECONDS,
                List.of(identity(Channel.ONEBOT, "123", "canonical-a"),
                        identity(Channel.ONEBOT, "123", "canonical-b"),
                        identity(Channel.QQ_OFFICIAL, "openid-A", "canonical-a")),
                obs("onebot", Channel.ONEBOT, "123", BASE, "hello"),
                obs("official", Channel.QQ_OFFICIAL, "openid-A", BASE, "hello"));

        assertThat(result.counts().unverifiedObservations()).isEqualTo(2);
        assertThat(result.pairs()).isEmpty();
    }

    @Test
    void oppositeDirectionsDoNotMatch() {
        MatchResult result = match(MatchingMode.STRICT, FIVE_SECONDS,
                List.of(identity(Channel.ONEBOT, "123", "canonical-a"),
                        identity(Channel.QQ_OFFICIAL, "openid-A", "canonical-a")),
                obs("onebot", Channel.ONEBOT, "123", BASE, Direction.INBOUND, "hello"),
                obs("official", Channel.QQ_OFFICIAL, "openid-A", BASE, Direction.OUTBOUND, "hello"));

        assertThat(result.counts().matchedObservations()).isZero();
        assertThat(result.counts().channelOnlyObservations()).isEqualTo(2);
        assertThat(result.pairs()).isEmpty();
    }

    @Test
    void emptyTextWithEqualMediaTypeCountAndMimeMatchesWhileUrlsAreIgnored() {
        MatchResult result = match(MatchingMode.STRICT, FIVE_SECONDS,
                List.of(identity(Channel.ONEBOT, "123", "canonical-a"),
                        identity(Channel.QQ_OFFICIAL, "openid-A", "canonical-a")),
                obs("onebot", Channel.ONEBOT, "123", BASE,
                        media("image", "image/png", "https://one.example/a?rkey=one"),
                        media("file", "application/pdf", "https://one.example/b")),
                obs("official", Channel.QQ_OFFICIAL, "openid-A", BASE,
                        media("image", "image/png", "https://two.example/a"),
                        media("file", "application/pdf", "https://two.example/b?rkey=two")));

        assertThat(result.counts().matchedPairs()).isEqualTo(1);
    }

    @Test
    void differentMimeNeverMatchesAndBecomesConflictWithSameIdentity() {
        MatchResult result = match(MatchingMode.STRICT, FIVE_SECONDS,
                List.of(identity(Channel.ONEBOT, "123", "canonical-a"),
                        identity(Channel.QQ_OFFICIAL, "openid-A", "canonical-a")),
                obs("onebot", Channel.ONEBOT, "123", BASE, media("image", "image/png", "u1")),
                obs("official", Channel.QQ_OFFICIAL, "openid-A", BASE,
                        media("image", "image/jpeg", "u2")));

        assertThat(result.counts().matchedObservations()).isZero();
        assertThat(result.counts().conflictObservations()).isEqualTo(2);
        assertThat(result.pairs()).singleElement()
                .extracting(MatchPair::state).isEqualTo(ObservationState.CONFLICT);
    }

    @Test
    void missingMimeOnOneOrEitherSideIsUnverifiedNotMatched() {
        List<IdentityMapping> identities = List.of(
                identity(Channel.ONEBOT, "123", "canonical-a"),
                identity(Channel.QQ_OFFICIAL, "openid-A", "canonical-a"));

        MatchResult oneMissing = match(MatchingMode.STRICT, FIVE_SECONDS, identities,
                obs("onebot", Channel.ONEBOT, "123", BASE, media("image", "image/png", "u1")),
                obs("official", Channel.QQ_OFFICIAL, "openid-A", BASE,
                        media("image", null, "u2")));
        assertThat(oneMissing.counts().matchedObservations()).isZero();
        assertThat(oneMissing.counts().unverifiedObservations()).isEqualTo(2);
        assertThat(oneMissing.pairs()).isEmpty();

        MatchResult bothMissing = match(MatchingMode.STRICT, FIVE_SECONDS, identities,
                obs("onebot", Channel.ONEBOT, "123", BASE, media("image", null, "u1")),
                obs("official", Channel.QQ_OFFICIAL, "openid-A", BASE,
                        media("image", null, "u2")));
        assertThat(bothMissing.counts().unverifiedObservations()).isEqualTo(2);
    }

    @Test
    void sameIdentityWithDifferentMediaCountsOrTypesIsConflict() {
        List<IdentityMapping> identities = List.of(
                identity(Channel.ONEBOT, "123", "canonical-a"),
                identity(Channel.QQ_OFFICIAL, "openid-A", "canonical-a"));

        MatchResult countMismatch = match(MatchingMode.STRICT, FIVE_SECONDS, identities,
                obs("onebot", Channel.ONEBOT, "123", BASE,
                        new Segment("text", "x"),
                        media("image", "image/png", "u1"),
                        media("image", "image/png", "u2")),
                obs("official", Channel.QQ_OFFICIAL, "openid-A", BASE,
                        new Segment("text", "x"),
                        media("image", "image/png", "u3")));
        assertThat(countMismatch.counts().conflictObservations()).isEqualTo(2);
        assertThat(countMismatch.counts().conflictPairs()).isEqualTo(1);

        MatchResult typeMismatch = match(MatchingMode.STRICT, FIVE_SECONDS, identities,
                obs("onebot", Channel.ONEBOT, "123", BASE,
                        new Segment("text", "x"),
                        media("image", "image/png", "u1")),
                obs("official", Channel.QQ_OFFICIAL, "openid-A", BASE,
                        new Segment("text", "x"),
                        media("video", "video/mp4", "u2")));
        assertThat(typeMismatch.counts().conflictObservations()).isEqualTo(2);
    }

    @Test
    void emptyTextVersusMediaIsConflictWithSameIdentity() {
        MatchResult result = match(MatchingMode.STRICT, FIVE_SECONDS,
                List.of(identity(Channel.ONEBOT, "123", "canonical-a"),
                        identity(Channel.QQ_OFFICIAL, "openid-A", "canonical-a")),
                obs("onebot", Channel.ONEBOT, "123", BASE, new Segment("text", "")),
                obs("official", Channel.QQ_OFFICIAL, "openid-A", BASE,
                        media("image", "image/png", "u1")));

        assertThat(result.counts().conflictObservations()).isEqualTo(2);
        assertThat(result.pairs()).singleElement()
                .extracting(MatchPair::state).isEqualTo(ObservationState.CONFLICT);
    }

    @Test
    void cardUnreliableReferencesAndUnknownSegmentsFailClosed() {
        List<IdentityMapping> identities = List.of(
                identity(Channel.ONEBOT, "123", "canonical-a"),
                identity(Channel.QQ_OFFICIAL, "openid-A", "canonical-a"));

        for (String type : List.of("card", "quote", "forward", "json", "unknown-rich")) {
            MatchResult result = match(MatchingMode.STRICT, FIVE_SECONDS, identities,
                    obs("onebot", Channel.ONEBOT, "123", BASE,
                            new Segment("text", "same body"),
                            new Segment(type, "[marker]", Map.of("auth_token", "secret-token"))),
                    obs("official", Channel.QQ_OFFICIAL, "openid-A", BASE,
                            new Segment("text", "same body")));

            assertThat(result.counts().matchedObservations())
                    .as("segment type %s", type)
                    .isZero();
            assertThat(result.counts().unverifiedObservations()).isEqualTo(2);
            assertThat(result.pairs()).isEmpty();
        }
    }

    @Test
    void unknownIncompleteOrTruncatedContentIntegrityNeverMatches() {
        List<IdentityMapping> identities = List.of(
                identity(Channel.ONEBOT, "123", "canonical-a"),
                identity(Channel.QQ_OFFICIAL, "openid-A", "canonical-a"));

        MatchResult loneUnknown = match(MatchingMode.STRICT, FIVE_SECONDS, identities,
                obsWithFlags("lone", Channel.ONEBOT, "123", BASE, ContentIntegrity.UNKNOWN,
                        TimeReliability.RELIABLE, true, new Segment("text", "same"),
                        ONEBOT_ACCOUNT, ONEBOT_SOURCE, "msg-lone"));
        assertThat(loneUnknown.counts().unverifiedObservations()).isEqualTo(1);
        assertThat(loneUnknown.counts().channelOnlyObservations()).isZero();

        for (ContentIntegrity integrity : List.of(ContentIntegrity.UNKNOWN,
                ContentIntegrity.INCOMPLETE, ContentIntegrity.TRUNCATED)) {
            MatchResult result = match(MatchingMode.STRICT, FIVE_SECONDS, identities,
                    obsWithFlags("onebot", Channel.ONEBOT, "123", BASE, integrity,
                            TimeReliability.RELIABLE, true, new Segment("text", "same"),
                            ONEBOT_ACCOUNT, ONEBOT_SOURCE, "msg-onebot"),
                    obs("official", Channel.QQ_OFFICIAL, "openid-A", BASE, "same"));
            assertThat(result.counts().matchedObservations())
                    .as("integrity %s", integrity)
                    .isZero();
            assertThat(result.counts().unverifiedObservations()).isEqualTo(2);
            assertThat(result.pairs()).isEmpty();
        }
    }

    @Test
    void fallbackOrUnknownTimestampReliabilityNeverMatches() {
        List<IdentityMapping> identities = List.of(
                identity(Channel.ONEBOT, "123", "canonical-a"),
                identity(Channel.QQ_OFFICIAL, "openid-A", "canonical-a"));

        for (TimeReliability reliability : List.of(TimeReliability.FALLBACK,
                TimeReliability.UNKNOWN)) {
            MatchResult result = match(MatchingMode.STRICT, FIVE_SECONDS, identities,
                    obs("onebot", Channel.ONEBOT, "123", BASE, "same"),
                    obsWithFlags("official", Channel.QQ_OFFICIAL, "openid-A", BASE,
                            ContentIntegrity.COMPLETE, reliability, true,
                            new Segment("text", "same"), OFFICIAL_ACCOUNT, OFFICIAL_SOURCE,
                            "msg-official"));
            assertThat(result.counts().matchedObservations())
                    .as("time reliability %s", reliability)
                    .isZero();
            assertThat(result.counts().unverifiedObservations()).isEqualTo(2);
            assertThat(result.pairs()).isEmpty();
        }
    }

    @Test
    void emptySegmentsRequireExplicitCompleteEvidenceAndCompatibilityDefaultsFailClosed() {
        List<IdentityMapping> identities = List.of(
                identity(Channel.ONEBOT, "123", "canonical-a"),
                identity(Channel.QQ_OFFICIAL, "openid-A", "canonical-a"));

        MatchResult unknown = match(MatchingMode.STRICT, FIVE_SECONDS, identities,
                obsWithFlags("onebot", Channel.ONEBOT, "123", BASE, ContentIntegrity.UNKNOWN,
                        TimeReliability.RELIABLE, true, List.of(),
                        ONEBOT_ACCOUNT, ONEBOT_SOURCE, "msg-onebot"),
                obsWithFlags("official", Channel.QQ_OFFICIAL, "openid-A", BASE,
                        ContentIntegrity.COMPLETE, TimeReliability.RELIABLE, true, List.of(),
                        OFFICIAL_ACCOUNT, OFFICIAL_SOURCE, "msg-official"));
        assertThat(unknown.counts().matchedObservations()).isZero();
        assertThat(unknown.counts().unverifiedObservations()).isEqualTo(2);

        MatchResult complete = match(MatchingMode.STRICT, FIVE_SECONDS, identities,
                obsWithFlags("onebot", Channel.ONEBOT, "123", BASE, ContentIntegrity.COMPLETE,
                        TimeReliability.RELIABLE, true, List.of(),
                        ONEBOT_ACCOUNT, ONEBOT_SOURCE, "msg-onebot"),
                obsWithFlags("official", Channel.QQ_OFFICIAL, "openid-A", BASE,
                        ContentIntegrity.COMPLETE, TimeReliability.RELIABLE, true, List.of(),
                        OFFICIAL_ACCOUNT, OFFICIAL_SOURCE, "msg-official"));
        assertThat(complete.counts().matchedPairs()).isEqualTo(1);

        MatchObservation legacy = new MatchObservation("legacy", THREAD, Channel.ONEBOT,
                ONEBOT_SOURCE, "msg-legacy", "123", Direction.INBOUND, BASE,
                List.of(new Segment("text", "hello")));
        assertThatThrownBy(() -> new MatchInput(THREAD, MatchingMode.STRICT, FIVE_SECONDS,
                List.of(), List.of(), List.of(legacy)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active channel binding");
    }

    @Test
    void looseWithoutMappingCanOnlyProduceUnverifiedMatch() {
        MatchResult unmapped = match(MatchingMode.LOOSE, FIVE_SECONDS, List.of(),
                obs("onebot", Channel.ONEBOT, "123", BASE, "hello"),
                obs("official", Channel.QQ_OFFICIAL, "openid-A", BASE, "hello"));
        assertThat(unmapped.counts().matchedObservations()).isZero();
        assertThat(unmapped.counts().unverifiedMatchObservations()).isEqualTo(2);
        assertThat(unmapped.counts().unverifiedMatchPairs()).isEqualTo(1);
        assertThat(unmapped.pairs()).singleElement()
                .extracting(MatchPair::state).isEqualTo(ObservationState.UNVERIFIED_MATCH);

        MatchResult mapped = match(MatchingMode.LOOSE, FIVE_SECONDS,
                List.of(identity(Channel.ONEBOT, "123", "canonical-a"),
                        identity(Channel.QQ_OFFICIAL, "openid-A", "canonical-a")),
                obs("onebot", Channel.ONEBOT, "123", BASE, "hello"),
                obs("official", Channel.QQ_OFFICIAL, "openid-A", BASE, "hello"));
        assertThat(mapped.counts().matchedObservations()).isZero();
        assertThat(mapped.counts().unverifiedMatchObservations()).isEqualTo(2);
    }

    @Test
    void looseAmbiguityStaysUnverifiedAndKnownDifferentIdentitiesDoNotPair() {
        MatchResult ambiguous = match(MatchingMode.LOOSE, FIVE_SECONDS, List.of(),
                obs("onebot", Channel.ONEBOT, "123", BASE, "same"),
                obs("official-a", Channel.QQ_OFFICIAL, "openid-A", BASE, "same"),
                obs("official-b", Channel.QQ_OFFICIAL, "openid-B", BASE, "same"));
        assertThat(ambiguous.counts().unverifiedObservations()).isEqualTo(3);
        assertThat(ambiguous.pairs()).isEmpty();

        MatchResult different = match(MatchingMode.LOOSE, FIVE_SECONDS,
                List.of(identity(Channel.ONEBOT, "123", "canonical-a"),
                        identity(Channel.QQ_OFFICIAL, "openid-A", "canonical-b")),
                obs("onebot", Channel.ONEBOT, "123", BASE, "hello"),
                obs("official", Channel.QQ_OFFICIAL, "openid-A", BASE, "hello"));
        assertThat(different.counts().channelOnlyObservations()).isEqualTo(2);
        assertThat(different.pairs()).isEmpty();
    }

    @Test
    void offNeverMatchesEvenWithExactContentAndIdentity() {
        MatchResult result = match(MatchingMode.OFF, FIVE_SECONDS,
                List.of(identity(Channel.ONEBOT, "123", "canonical-a"),
                        identity(Channel.QQ_OFFICIAL, "openid-A", "canonical-a")),
                obs("onebot", Channel.ONEBOT, "123", BASE, "hello"),
                obs("official", Channel.QQ_OFFICIAL, "openid-A", BASE, "hello"));

        assertThat(result.counts().matchedObservations()).isZero();
        assertThat(result.counts().matchedPairs()).isZero();
        assertThat(result.counts().channelOnlyObservations()).isEqualTo(2);
        assertThat(result.pairs()).isEmpty();
    }

    @Test
    void lateDuplicateTriggersFullRecomputationAndDemotesEarlierMatched() {
        List<IdentityMapping> identities = List.of(
                identity(Channel.ONEBOT, "123", "canonical-a"),
                identity(Channel.QQ_OFFICIAL, "openid-A", "canonical-a"),
                identity(Channel.QQ_OFFICIAL, "openid-B", "canonical-a"));

        MatchResult oneChannel = match(MatchingMode.STRICT, FIVE_SECONDS, identities,
                obs("onebot", Channel.ONEBOT, "123", BASE, "hello"));
        assertThat(oneChannel.counts().channelOnlyObservations()).isEqualTo(1);

        MatchResult pair = match(MatchingMode.STRICT, FIVE_SECONDS, identities,
                obs("onebot", Channel.ONEBOT, "123", BASE, "hello"),
                obs("official-a", Channel.QQ_OFFICIAL, "openid-A", BASE, "hello"));
        assertThat(pair.counts().matchedPairs()).isEqualTo(1);

        MatchResult lateDuplicate = match(MatchingMode.STRICT, FIVE_SECONDS, identities,
                obs("onebot", Channel.ONEBOT, "123", BASE, "hello"),
                obs("official-a", Channel.QQ_OFFICIAL, "openid-A", BASE, "hello"),
                obs("official-b", Channel.QQ_OFFICIAL, "openid-B", BASE, "hello"));
        assertThat(lateDuplicate.counts().matchedPairs()).isZero();
        assertThat(lateDuplicate.counts().unverifiedObservations()).isEqualTo(3);
        assertThat(lateDuplicate.counts().channelOnlyObservations()).isZero();
    }

    @Test
    void nfkcAndWhitespaceNormalizeButLiteralCqAtAndArbitraryPrefixesDoNot() {
        List<IdentityMapping> identities = List.of(
                identity(Channel.ONEBOT, "123", "canonical-a"),
                identity(Channel.QQ_OFFICIAL, "openid-A", "canonical-a"));

        MatchResult nfkc = match(MatchingMode.STRICT, FIVE_SECONDS, identities,
                obs("onebot", Channel.ONEBOT, "123", BASE, "  Ｈｅｌｌｏ\t  ｗｏｒｌｄ "),
                obs("official", Channel.QQ_OFFICIAL, "openid-A", BASE, "Hello world"));
        assertThat(nfkc.counts().matchedPairs()).isEqualTo(1);

        MatchResult literalCq = match(MatchingMode.STRICT, FIVE_SECONDS, identities,
                obs("onebot", Channel.ONEBOT, "123", BASE, "[CQ:at,qq=1] hello"),
                obs("official", Channel.QQ_OFFICIAL, "openid-A", BASE, "hello"));
        assertThat(literalCq.counts().matchedObservations()).isZero();
        assertThat(literalCq.counts().conflictObservations()).isEqualTo(2);

        MatchResult literalAt = match(MatchingMode.STRICT, FIVE_SECONDS, identities,
                obs("onebot", Channel.ONEBOT, "123", BASE, "@bot hello"),
                obs("official", Channel.QQ_OFFICIAL, "openid-A", BASE, "hello"));
        assertThat(literalAt.counts().matchedObservations()).isZero();
        assertThat(literalAt.counts().conflictObservations()).isEqualTo(2);
    }

    @Test
    void trustedStructuredMentionIsIgnoredButMentionOnlyOrUntrustedMentionFailsClosed() {
        List<IdentityMapping> identities = List.of(
                identity(Channel.ONEBOT, "123", "canonical-a"),
                identity(Channel.QQ_OFFICIAL, "openid-A", "canonical-a"));

        MatchResult trusted = match(MatchingMode.STRICT, FIVE_SECONDS, identities,
                obs("onebot", Channel.ONEBOT, "123", BASE,
                        new Segment("at", "@机器人"),
                        new Segment("text", "hello")),
                obs("official", Channel.QQ_OFFICIAL, "openid-A", BASE, "hello"));
        assertThat(trusted.counts().matchedPairs()).isEqualTo(1);

        MatchResult mentionOnly = match(MatchingMode.STRICT, FIVE_SECONDS, identities,
                obs("onebot", Channel.ONEBOT, "123", BASE, new Segment("at", "@机器人")),
                obs("official", Channel.QQ_OFFICIAL, "openid-A", BASE, new Segment("text", "")));
        assertThat(mentionOnly.counts().matchedObservations()).isZero();
        assertThat(mentionOnly.counts().unverifiedObservations()).isEqualTo(2);

        MatchObservation untrustedMention = obsWithFlags("official", Channel.QQ_OFFICIAL,
                "openid-A", BASE, ContentIntegrity.COMPLETE, TimeReliability.RELIABLE, false,
                new Segment("at", "@机器人"), OFFICIAL_ACCOUNT, OFFICIAL_SOURCE, "msg-official");
        MatchResult untrusted = match(MatchingMode.STRICT, FIVE_SECONDS, identities,
                obs("onebot", Channel.ONEBOT, "123", BASE, "hello"),
                untrustedMention);
        assertThat(untrusted.counts().matchedObservations()).isZero();
        assertThat(untrusted.counts().unverifiedObservations()).isEqualTo(2);
    }

    @Test
    void segmentMetadataUrlsAndRkeysAreIgnoredAndResultsAreNotBodyDerived() {
        String body = "TOP-SECRET-BODY-7f9a";
        String token = "AUTH-TOKEN-4d2c";
        Segment onebotImage = new Segment("image", "[图片]", Map.of(
                "mime", "image/png",
                "url", "https://cdn.one.example/img?rkey=secret-one",
                "query", "auth_token=secret-one"));
        Segment officialImage = new Segment("image", "photo", Map.of(
                "mime", "image/png",
                "url", "https://cdn.two.example/img",
                "metadata", "different"));
        MatchObservation onebot = obsWithFlags("onebot", Channel.ONEBOT, "123", BASE,
                ContentIntegrity.COMPLETE, TimeReliability.RELIABLE, true,
                List.of(new Segment("text", body, Map.of("auth_token", token)), onebotImage),
                ONEBOT_ACCOUNT, ONEBOT_SOURCE, "msg-onebot");
        MatchObservation official = obsWithFlags("official", Channel.QQ_OFFICIAL, "openid-A", BASE,
                ContentIntegrity.COMPLETE, TimeReliability.RELIABLE, true,
                List.of(new Segment("text", body, Map.of("auth_token", token)), officialImage),
                OFFICIAL_ACCOUNT, OFFICIAL_SOURCE, "msg-official");
        MatchInput input = new MatchInput(THREAD, MatchingMode.STRICT, FIVE_SECONDS,
                bindings(), List.of(identity(Channel.ONEBOT, "123", "canonical-a"),
                        identity(Channel.QQ_OFFICIAL, "openid-A", "canonical-a")),
                List.of(onebot, official));

        MatchResult result = matcher.recompute(input);

        assertThat(result.counts().matchedPairs()).isEqualTo(1);
        assertThat(input.toString()).doesNotContain(body, token);
        assertThat(onebot.toString()).doesNotContain(body, token);
        assertThat(result.toString()).doesNotContain(body, token, "secret-one", "rkey");
        assertThat(result.observations().toString()).doesNotContain(body, token);
        assertThat(result.pairs().toString()).doesNotContain(body, token);
        assertThat(result.toString()).doesNotContainPattern("[0-9a-fA-F]{64}");
        assertThat(result.observations()).extracting(item -> item.observation().rawSource())
                .containsExactlyInAnyOrder(ONEBOT_SOURCE, OFFICIAL_SOURCE);
    }

    @Test
    void resultIsDeterministicAcrossInputOrdering() {
        MatchObservation onebot = obs("onebot", Channel.ONEBOT, "123", BASE, "hello");
        MatchObservation official = obs("official", Channel.QQ_OFFICIAL, "openid-A",
                BASE.plusSeconds(1), "hello");
        List<IdentityMapping> identities = List.of(
                identity(Channel.ONEBOT, "123", "canonical-a"),
                identity(Channel.QQ_OFFICIAL, "openid-A", "canonical-a"));

        MatchResult first = match(MatchingMode.STRICT, FIVE_SECONDS, identities,
                onebot, official);
        MatchResult second = match(MatchingMode.STRICT, FIVE_SECONDS, identities,
                official, onebot);

        assertThat(first.pairs()).isEqualTo(second.pairs());
        assertThat(first.observations()).isEqualTo(second.observations());
        assertThat(first.counts()).isEqualTo(second.counts());
    }

    @Test
    void snapshotAndMessageLimitsRejectOversizeInputWithoutRenderingContent() {
        MatchObservation observation = obs("onebot", Channel.ONEBOT, "123", BASE, "hello");

        assertThatThrownBy(() -> new MatchInput(THREAD, MatchingMode.STRICT,
                Duration.ofSeconds(-1), bindings(), List.of(), List.of(observation)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0..300");
        assertThatThrownBy(() -> new MatchInput(THREAD, MatchingMode.STRICT,
                Duration.ofSeconds(301), bindings(), List.of(), List.of(observation)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0..300");

        List<MatchObservation> tooManyObservations = new ArrayList<>();
        for (int index = 0; index <= MatchingLimits.MAX_OBSERVATIONS; index++) {
            tooManyObservations.add(obs("obs-" + index, Channel.ONEBOT, "123", BASE, "x"));
        }
        assertThatThrownBy(() -> new MatchInput(THREAD, MatchingMode.STRICT, FIVE_SECONDS,
                bindings(), List.of(), tooManyObservations))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("observations 超过上限");

        List<IdentityMapping> tooManyIdentities = IntStream
                .rangeClosed(0, MatchingLimits.MAX_IDENTITIES)
                .mapToObj(index -> identity(Channel.ONEBOT, "sender-" + index,
                        "canonical-" + index))
                .toList();
        assertThatThrownBy(() -> new MatchInput(THREAD, MatchingMode.STRICT, FIVE_SECONDS,
                bindings(), tooManyIdentities, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identities 超过上限");

        List<Segment> tooManySegments = IntStream
                .rangeClosed(0, MatchingLimits.MAX_SEGMENTS_PER_OBSERVATION)
                .mapToObj(index -> new Segment("text", "x"))
                .toList();
        assertThatThrownBy(() -> obsWithFlags("too-many-segments", Channel.ONEBOT, "123", BASE,
                ContentIntegrity.COMPLETE, TimeReliability.RELIABLE, true,
                tooManySegments, ONEBOT_ACCOUNT, ONEBOT_SOURCE, "msg-too-many"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("segments 超过上限");

        String tooLong = "x".repeat(MatchingLimits.MAX_TEXT_CODE_POINTS_PER_OBSERVATION + 1);
        assertThatThrownBy(() -> obs("too-long", Channel.ONEBOT, "123", BASE, tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("正文超过上限");
    }

    @Test
    void duplicateBindingsSourcesAndMessagesAreRejected() {
        ActiveChannelBinding onebot = new ActiveChannelBinding(THREAD, Channel.ONEBOT,
                ONEBOT_ACCOUNT, ONEBOT_SOURCE);
        ActiveChannelBinding official = new ActiveChannelBinding(THREAD, Channel.QQ_OFFICIAL,
                OFFICIAL_ACCOUNT, OFFICIAL_SOURCE);
        ActiveChannelBinding duplicateChannel = new ActiveChannelBinding(THREAD, Channel.ONEBOT,
                ONEBOT_ACCOUNT, "qq_group/bot-1/999");

        assertThatThrownBy(() -> new MatchInput(THREAD, MatchingMode.STRICT, FIVE_SECONDS,
                List.of(onebot, duplicateChannel), List.of(),
                List.of(obs("onebot", Channel.ONEBOT, "123", BASE, "hello"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("同一 channel");

        MatchObservation one = obs("one", Channel.ONEBOT, "123", BASE, "hello");
        MatchObservation duplicateMessage = obsWithFlags("two", Channel.ONEBOT, "123", BASE,
                ContentIntegrity.COMPLETE, TimeReliability.RELIABLE, true,
                new Segment("text", "different"), ONEBOT_ACCOUNT, ONEBOT_SOURCE, "msg-one");
        assertThatThrownBy(() -> new MatchInput(THREAD, MatchingMode.STRICT, FIVE_SECONDS,
                List.of(onebot, official), List.of(), List.of(one, duplicateMessage)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channelMessageId 重复");
    }

    private MatchResult match(MatchingMode mode, Duration tolerance,
                              List<IdentityMapping> identities,
                              MatchObservation... observations) {
        return matcher.recompute(new MatchInput(THREAD, mode, tolerance, bindings(),
                identities, List.of(observations)));
    }

    private static List<ActiveChannelBinding> bindings() {
        return List.of(
                new ActiveChannelBinding(THREAD, Channel.ONEBOT, ONEBOT_ACCOUNT, ONEBOT_SOURCE),
                new ActiveChannelBinding(THREAD, Channel.QQ_OFFICIAL, OFFICIAL_ACCOUNT,
                        OFFICIAL_SOURCE));
    }

    private static IdentityMapping identity(Channel channel, String sender, String canonical) {
        return new IdentityMapping(THREAD, channel, sender, canonical,
                IdentityMapping.ScopeType.THREAD, THREAD);
    }

    private static Segment media(String type, String mime, String url) {
        Map<String, String> data = mime == null
                ? Map.of("url", url)
                : Map.of("mime", mime, "url", url);
        return new Segment(type, "[" + type + "]", data);
    }

    private static MatchObservation obs(String id, Channel channel, String sender, Instant at,
                                        String text) {
        return obs(id, channel, sender, at, Direction.INBOUND, new Segment("text", text));
    }

    private static MatchObservation obs(String id, Channel channel, String sender, Instant at,
                                        Direction direction, String text) {
        return obs(id, channel, sender, at, direction, new Segment("text", text));
    }

    private static MatchObservation obs(String id, Channel channel, String sender, Instant at,
                                        Segment... segments) {
        return obs(id, channel, sender, at, Direction.INBOUND, segments);
    }

    private static MatchObservation obs(String id, Channel channel, String sender, Instant at,
                                        Direction direction, Segment... segments) {
        return new MatchObservation(id, THREAD, channel, account(channel), source(channel),
                "msg-" + id, sender, direction, at, ContentIntegrity.COMPLETE,
                TimeReliability.RELIABLE, true, List.of(segments));
    }

    private static MatchObservation obsWithFlags(String id, Channel channel, String sender,
                                                 Instant at,
                                                 ContentIntegrity integrity,
                                                 TimeReliability timeReliability,
                                                 boolean mentionsTrusted,
                                                 Segment segment,
                                                 String accountId,
                                                 String rawSource,
                                                 String messageId) {
        return obsWithFlags(id, channel, sender, at, integrity, timeReliability, mentionsTrusted,
                List.of(segment), accountId, rawSource, messageId);
    }

    private static MatchObservation obsWithFlags(String id, Channel channel, String sender,
                                                 Instant at,
                                                 ContentIntegrity integrity,
                                                 TimeReliability timeReliability,
                                                 boolean mentionsTrusted,
                                                 List<Segment> segments,
                                                 String accountId,
                                                 String rawSource,
                                                 String messageId) {
        List<Segment> list = segments == null ? List.of() : segments;
        return new MatchObservation(id, THREAD, channel, accountId, rawSource, messageId,
                sender, Direction.INBOUND, at, integrity, timeReliability, mentionsTrusted, list);
    }

    private static String account(Channel channel) {
        return channel == Channel.ONEBOT ? ONEBOT_ACCOUNT : OFFICIAL_ACCOUNT;
    }

    private static String source(Channel channel) {
        return channel == Channel.ONEBOT ? ONEBOT_SOURCE : OFFICIAL_SOURCE;
    }
}
