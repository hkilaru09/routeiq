package com.routeiq.notify;

import com.routeiq.domain.Issue;
import com.routeiq.domain.Team;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Only active when an SMTP server is configured ({@code spring.mail.host}). */
@Component
@ConditionalOnExpression("!'${spring.mail.host:}'.isBlank()")
public class EmailSender implements NotificationSender {

    private final JavaMailSender mail;
    private final String from;

    public EmailSender(JavaMailSender mail, @Value("${routeiq.notifier.email.from:routeiq@routeiq.local}") String from) {
        this.mail = mail;
        this.from = from;
    }

    @Override
    public String channel() {
        return "EMAIL";
    }

    @Override
    public String target(Team team) {
        return team.email();
    }

    @Override
    public void send(String target, String kind, Issue issue, Team team) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(target);
        message.setSubject("[RouteIQ][%s] %s: %s".formatted(issue.tenantId(), kind, issue.title()));
        message.setText("""
                Issue %s was %s for team %s.

                Category: %s
                SLA due:  %s
                Escalation level: %d

                %s
                """.formatted(issue.id(), kind.toLowerCase(), team.name(), issue.category(), issue.slaDueAt(),
                issue.escalationLevel(), issue.description() == null ? "" : issue.description()));
        mail.send(message);
    }
}
