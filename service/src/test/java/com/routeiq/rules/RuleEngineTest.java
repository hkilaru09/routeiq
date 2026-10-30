package com.routeiq.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.routeiq.domain.Operator;
import com.routeiq.domain.Routable;
import com.routeiq.domain.RoutingRule;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuleEngineTest {

    private final RuleEngine engine = new RuleEngine();

    record Probe(String externalId, String title, String description, String category,
                 Map<String, String> attributes) implements Routable {
    }

    private static Probe issue(String category, Map<String, String> attributes) {
        return new Probe("x-1", "Something happened", "details", category, attributes);
    }

    private static RoutingRule rule(String name, int priority, String field, Operator op, String value) {
        return new RoutingRule(UUID.randomUUID(), "t", name, priority, field, op, value, UUID.randomUUID(), 60, true);
    }

    @Test
    void lowestPriorityMatchWins() {
        var broad = rule("broad", 20, "category", Operator.CONTAINS, "credit");
        var narrow = rule("narrow", 10, "category", Operator.PREFIX, "credit reporting");
        var match = engine.match(List.of(broad, narrow), issue("Credit reporting or other personal consumer reports", Map.of()));
        assertThat(match).contains(narrow);
    }

    @Test
    void disabledRulesAreSkipped() {
        var disabled = new RoutingRule(UUID.randomUUID(), "t", "off", 1, "category", Operator.EQUALS, "noise",
                UUID.randomUUID(), 60, false);
        assertThat(engine.match(List.of(disabled), issue("Noise", Map.of()))).isEmpty();
    }

    @Test
    void operatorsAreCaseInsensitive() {
        Probe p = issue("Noise - Residential", Map.of("agency", "NYPD"));
        assertThat(engine.matches(rule("eq", 1, "attr.agency", Operator.EQUALS, "nypd"), p)).isTrue();
        assertThat(engine.matches(rule("prefix", 1, "category", Operator.PREFIX, "NOISE"), p)).isTrue();
        assertThat(engine.matches(rule("contains", 1, "category", Operator.CONTAINS, "residential"), p)).isTrue();
        assertThat(engine.matches(rule("in", 1, "attr.agency", Operator.IN, "DOT, nypd ,HPD"), p)).isTrue();
        assertThat(engine.matches(rule("regex", 1, "category", Operator.REGEX, "^noise\\s*-"), p)).isTrue();
        assertThat(engine.matches(rule("in-miss", 1, "attr.agency", Operator.IN, "DOT,HPD"), p)).isFalse();
    }

    @Test
    void missingFieldNeverMatches() {
        Probe p = issue(null, Map.of());
        assertThat(engine.matches(rule("cat", 1, "category", Operator.CONTAINS, "a"), p)).isFalse();
        assertThat(engine.matches(rule("attr", 1, "attr.borough", Operator.EQUALS, "BRONX"), p)).isFalse();
    }

    @Test
    void validationRejectsBadRules() {
        assertThat(RuleEngine.validate("category", "REGEX", "(unclosed")).get().asString().contains("invalid regex");
        assertThat(RuleEngine.validate("priority", "EQUALS", "x")).isPresent();
        assertThat(RuleEngine.validate("attr.", "EQUALS", "x")).isPresent();
        assertThat(RuleEngine.validate("category", "LIKE", "x")).isPresent();
        assertThat(RuleEngine.validate("attr.agency", "IN", "DOT,HPD")).isEmpty();
    }
}
