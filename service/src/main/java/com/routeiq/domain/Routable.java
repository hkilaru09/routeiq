package com.routeiq.domain;

import java.util.Map;

/** The parts of an issue that routing rules can match on. */
public interface Routable {
    String externalId();

    String title();

    String description();

    String category();

    Map<String, String> attributes();
}
