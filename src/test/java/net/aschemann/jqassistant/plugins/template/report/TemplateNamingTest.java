package net.aschemann.jqassistant.plugins.template.report;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two pure naming helpers. They look trivial, but note the asymmetry they encode:
 * a rule id becomes a template name with {@code -} and an output file name with {@code _}
 * (the latter through jQAssistant's own {@code ReportHelper.escapeRuleId}). Anyone who
 * "harmonises" the two separators breaks either template lookup or the master report links.
 */
class TemplateNamingTest {

    @Test
    void replacesTheRuleIdSeparatorWithAHyphen() {
        assertThat(TemplateReportPlugin.ruleIdToTemplateName("demo:StoreInventory", ".adoc.ftl"))
            .isEqualTo("demo-StoreInventory.adoc.ftl");
    }

    @Test
    void appendsTheSuffixToAnIdWithoutGroup() {
        assertThat(TemplateReportPlugin.ruleIdToTemplateName("StoreInventory", ".adoc.ftl"))
            .isEqualTo("StoreInventory.adoc.ftl");
    }

    @Test
    void replacesEverySeparatorOfANestedId() {
        assertThat(TemplateReportPlugin.ruleIdToTemplateName("a:b:c", ".ftl"))
            .isEqualTo("a-b-c.ftl");
    }

    @Test
    void sanitizeKeepsCharactersThatAreSafeInAPathSegment() {
        assertThat(TemplateReportPlugin.sanitize("bundle.core_v1-2")).isEqualTo("bundle.core_v1-2");
    }

    @Test
    void sanitizeReplacesPathSeparatorsAndSpaces() {
        assertThat(TemplateReportPlugin.sanitize("org/acme my bundle")).isEqualTo("org_acme_my_bundle");
    }

    @Test
    void sanitizeFallsBackForNullAndEmptyKeys() {
        assertThat(TemplateReportPlugin.sanitize(null)).isEqualTo("_unset");
        assertThat(TemplateReportPlugin.sanitize("")).isEqualTo("_unset");
    }
}
