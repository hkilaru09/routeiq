package com.routeiq.rules;

import com.routeiq.domain.Operator;
import com.routeiq.domain.Routable;
import com.routeiq.domain.RoutingRule;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.stereotype.Component;

/**
 * Evaluates routing rules against an issue. Rules run in ascending priority and the first match wins.
 * All comparisons are case-insensitive. Fields are {@code category}, {@code title}, {@code description},
 * {@code externalId}, or {@code attr.<name>} for a free-form attribute.
 */
@Component
public class RuleEngine {

    private static final List<String> FIXED_FIELDS = List.of("category", "title", "description", "externalId");

    private final Map<String, Pattern> patterns = new ConcurrentHashMap<>();

    public Optional<RoutingRule> match(List<RoutingRule> rules, Routable issue) {
        return rules.stream()
                .filter(RoutingRule::enabled)
                .sorted(Comparator.comparingInt(RoutingRule::priority))
                .filter(rule -> matches(rule, issue))
                .findFirst();
    }

    boolean matches(RoutingRule rule, Routable issue) {
        String value = fieldValue(rule.field(), issue);
        if (value == null) {
            return false;
        }
        String actual = value.toLowerCase(Locale.ROOT);
        String expected = rule.matchValue().toLowerCase(Locale.ROOT);
        return switch (rule.operator()) {
            case EQUALS -> actual.equals(expected);
            case CONTAINS -> actual.contains(expected);
            case PREFIX -> actual.startsWith(expected);
            case IN -> Arrays.stream(expected.split(",")).map(String::trim).anyMatch(actual::equals);
            case REGEX -> pattern(rule.matchValue()).matcher(value).find();
        };
    }

    private Pattern pattern(String regex) {
        return patterns.computeIfAbsent(regex, r -> Pattern.compile(r, Pattern.CASE_INSENSITIVE));
    }

    static String fieldValue(String field, Routable issue) {
        if (field.startsWith("attr.")) {
            Map<String, String> attributes = issue.attributes();
            return attributes == null ? null : attributes.get(field.substring("attr.".length()));
        }
        return switch (field) {
            case "category" -> issue.category();
            case "title" -> issue.title();
            case "description" -> issue.description();
            case "externalId" -> issue.externalId();
            default -> null;
        };
    }

    /** Returns a validation error message, or empty if the rule is well-formed. */
    public static Optional<String> validate(String field, String operator, String matchValue) {
        if (field == null || !(FIXED_FIELDS.contains(field) || (field.startsWith("attr.") && field.length() > 5))) {
            return Optional.of("field must be one of " + FIXED_FIELDS + " or attr.<name>");
        }
        try {
            Operator op = Operator.valueOf(operator);
            if (op == Operator.REGEX) {
                Pattern.compile(matchValue);
            }
        } catch (IllegalArgumentException e) {
            if (e instanceof PatternSyntaxException pse) {
                return Optional.of("invalid regex: " + pse.getDescription());
            }
            return Optional.of("operator must be one of " + Arrays.toString(Operator.values()));
        }
        if (matchValue == null || matchValue.isBlank()) {
            return Optional.of("matchValue must not be blank");
        }
        return Optional.empty();
    }
}
