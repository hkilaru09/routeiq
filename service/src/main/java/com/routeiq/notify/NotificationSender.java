package com.routeiq.notify;

import com.routeiq.domain.Issue;
import com.routeiq.domain.Team;

/** One delivery channel. Implementations throw on failure so the message is retried. */
public interface NotificationSender {

    String channel();

    /** The address this channel would deliver to for the team, or null if the team has none. */
    String target(Team team);

    void send(String target, String kind, Issue issue, Team team);
}
