package io.quarkus.bot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import jakarta.inject.Inject;

import org.jboss.logging.Logger;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHIssue;

import io.quarkiverse.githubapp.ConfigFile;
import io.quarkiverse.githubapp.event.Issue;
import io.quarkus.bot.config.Feature;
import io.quarkus.bot.config.QuarkusGitHubBotConfig;
import io.quarkus.bot.config.QuarkusGitHubBotConfigFile;
import io.quarkus.bot.config.QuarkusGitHubBotConfigFile.TriageRule;
import io.quarkus.bot.util.GHIssues;
import io.quarkus.bot.util.Labels;
import io.quarkus.bot.util.Mentions;
import io.quarkus.bot.util.Strings;
import io.quarkus.bot.util.Triage;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

import org.kohsuke.github.GHLabel;

class TriageIssue {

    private static final Logger LOG = Logger.getLogger(TriageIssue.class);

    @Inject
    QuarkusGitHubBotConfig quarkusBotConfig;

    void triageIssue(@Issue.Opened GHEventPayload.Issue issuePayload,
            @ConfigFile("quarkus-github-bot.yml") QuarkusGitHubBotConfigFile quarkusBotConfigFile,
            DynamicGraphQLClient gitHubGraphQLClient) throws IOException {
        if (!Feature.TRIAGE_ISSUES_AND_PULL_REQUESTS.isEnabled(quarkusBotConfigFile)) {
            return;
        }

        if (quarkusBotConfigFile.triage.rules.isEmpty()) {
            return;
        }

        GHIssue issue = issuePayload.getIssue();
        Set<String> labels = new TreeSet<>();
        Mentions mentions = new Mentions();
        List<String> comments = new ArrayList<>();

        for (TriageRule rule : quarkusBotConfigFile.triage.rules) {
            if (Triage.matchRuleFromDescription(issue.getTitle(), issue.getBody(), rule)) {
                if (!rule.labels.isEmpty()) {
                    labels.addAll(rule.labels);
                }
                if (!rule.notify.isEmpty()) {
                    for (String mention : rule.notify) {
                        if (!mention.equals(issue.getUser().getLogin())) {
                            mentions.add(mention, rule.id);
                        }
                    }
                }
                if (Strings.isNotBlank(rule.comment)) {
                    comments.add(rule.comment);
                }
            }
        }

        // remove from the set the labels already present on the pull request
        issue.getLabels().stream().map(GHLabel::getName).forEach(labels::remove);

        if (!labels.isEmpty()) {
            if (!quarkusBotConfig.isDryRun()) {
                issue.addLabels(Labels.limit(labels).toArray(new String[0]));
            } else {
                LOG.info("Issue #" + issue.getNumber() + " - Add labels: " + String.join(", ", Labels.limit(labels)));
            }
        }

        mentions.removeAlreadyParticipating(GHIssues.getParticipatingUsers(issue, gitHubGraphQLClient));
        if (!mentions.isEmpty()) {
            comments.add("/cc " + mentions.getMentionsString());
        }

        for (String comment : comments) {
            if (!quarkusBotConfig.isDryRun()) {
                issue.comment(comment);
            } else {
                LOG.info("Issue #" + issue.getNumber() + " - Add comment: " + comment);
            }
        }

        if (mentions.isEmpty() && !Labels.hasAreaLabels(labels) && !GHIssues.hasAreaLabel(issue)
                && !GHIssues.hasLabel(issue, Labels.KIND_EXTENSION_PROPOSAL)) {
            if (!quarkusBotConfig.isDryRun()) {
                issue.addLabels(Labels.TRIAGE_NEEDS_TRIAGE);
            } else {
                LOG.info("Issue #" + issue.getNumber() + " - Add label: " + Labels.TRIAGE_NEEDS_TRIAGE);
            }
        }
    }
}
