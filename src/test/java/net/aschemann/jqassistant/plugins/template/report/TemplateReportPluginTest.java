package net.aschemann.jqassistant.plugins.template.report;

import com.buschmais.jqassistant.core.report.api.ReportException;
import com.buschmais.jqassistant.core.report.api.model.Column;
import com.buschmais.jqassistant.core.report.api.model.Result;
import com.buschmais.jqassistant.core.report.api.model.Row;
import com.buschmais.jqassistant.core.rule.api.model.Concept;
import com.buschmais.jqassistant.core.rule.api.model.Report;
import com.buschmais.jqassistant.core.rule.api.model.Severity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Renders through a real FreeMarker template against a real temporary filesystem.
 *
 * <p>The templates live in a temporary directory handed to the plugin via
 * {@code template.template-dir}, so these tests exercise the filesystem layer of the
 * template loader and never depend on the working directory of the build.
 */
class TemplateReportPluginTest {

    /**
     * Prints everything the data model is documented to expose, so each assertion below can
     * check the model rather than the formatting.
     */
    private static final String TEMPLATE = String.join("\n",
        "rule=${rule.id}",
        "severity=${rule.severity!'-'}",
        "columns=${columns?join(\",\")}",
        "<#if key??>key=${key}</#if>",
        "rows=${rows?size}",
        "<#list rows as row>row:${row.bundle}/${row.state}",
        "</#list>") + "\n";

    @TempDir
    Path tempDir;

    private Path templateDir;

    private File reportRoot;

    private StubReportContext reportContext;

    private TemplateReportPlugin plugin;

    @BeforeEach
    void setUp() throws IOException {
        templateDir = Files.createDirectories(tempDir.resolve("templates"));
        reportRoot = Files.createDirectories(tempDir.resolve("report")).toFile();
        reportContext = new StubReportContext(reportRoot);
        plugin = new TemplateReportPlugin();
    }

    @Test
    void writesOneFilePerRuleWhenNoPrimaryColumnIsSet() throws Exception {
        writeTemplate("demo-Thing.adoc.ftl");
        configure();

        plugin.setResult(result(concept("demo:Thing", null, new Properties()),
            row("core", "ACTIVE"), row("ui", "RESOLVED")));

        // Note the separator asymmetry: the template is looked up as demo-Thing.adoc.ftl,
        // the output file is named through ReportHelper.escapeRuleId -> demo_Thing.adoc.
        File output = new File(reportRoot, "asciidoc/demo_Thing.adoc");
        assertThat(output).exists();
        assertThat(contentOf(output))
            .contains("rule=demo:Thing")
            .contains("columns=bundle,state")
            .contains("rows=2")
            .contains("row:core/ACTIVE")
            .contains("row:ui/RESOLVED")
            .doesNotContain("key=");
        assertThat(reportContext.registered).hasSize(1);
        assertThat(reportContext.registered.get(0).label).isEqualTo("AsciiDoc");
        assertThat(reportContext.registered.get(0).ruleId).isEqualTo("demo:Thing");
    }

    @Test
    void groupsRowsIntoOneDirectoryPerPrimaryColumnValue() throws Exception {
        writeTemplate("demo-Thing.adoc.ftl");
        configure();

        plugin.setResult(result(concept("demo:Thing", "bundle", new Properties()),
            row("core", "ACTIVE"), row("core", "STARTING"), row("ui", "RESOLVED")));

        assertThat(contentOf(new File(reportRoot, "asciidoc/core/demo_Thing.adoc")))
            .contains("key=core")
            .contains("rows=2");
        assertThat(contentOf(new File(reportRoot, "asciidoc/ui/demo_Thing.adoc")))
            .contains("key=ui")
            .contains("rows=1");
        assertThat(reportContext.registered).hasSize(2);
    }

    @Test
    void replacesCharactersThatAreUnsafeInADirectoryName() throws Exception {
        writeTemplate("demo-Thing.adoc.ftl");
        configure();

        plugin.setResult(result(concept("demo:Thing", "bundle", new Properties()),
            row("org/acme ui", "ACTIVE")));

        assertThat(new File(reportRoot, "asciidoc/org_acme_ui/demo_Thing.adoc")).exists();
    }

    @Test
    void dropsGroupsThatDoNotMatchThePerRuleKeyPattern() throws Exception {
        writeTemplate("demo-Thing.adoc.ftl");
        configure();

        Properties properties = new Properties();
        properties.setProperty(TemplateReportPlugin.PROP_PRIMARY_KEY_PATTERN, "core.*");

        plugin.setResult(result(concept("demo:Thing", "bundle", properties),
            row("core", "ACTIVE"), row("ui", "RESOLVED")));

        assertThat(new File(reportRoot, "asciidoc/core/demo_Thing.adoc")).exists();
        assertThat(new File(reportRoot, "asciidoc/ui")).doesNotExist();
    }

    @Test
    void rendersEmptyStubsForKeysOnlyPresentInTheStubSourceRule() throws Exception {
        writeTemplate("demo-Universe.adoc.ftl");
        writeTemplate("demo-Section.adoc.ftl");
        configure();

        // The universe-source rule has to be processed first; in production that ordering
        // is enforced with <requiresConcept refId="demo:Universe"/>.
        plugin.setResult(result(concept("demo:Universe", "bundle", new Properties()),
            row("core", "ACTIVE"), row("ui", "RESOLVED")));

        Properties properties = new Properties();
        properties.setProperty(TemplateReportPlugin.PROP_STUB_SOURCE, "demo:Universe");
        plugin.setResult(result(concept("demo:Section", "bundle", properties),
            row("core", "ACTIVE")));

        assertThat(contentOf(new File(reportRoot, "asciidoc/core/demo_Section.adoc")))
            .contains("rows=1");
        File stub = new File(reportRoot, "asciidoc/ui/demo_Section.adoc");
        assertThat(stub).as("stub for the key that only the universe rule produced").exists();
        assertThat(contentOf(stub))
            .contains("key=ui")
            .contains("rows=0");
    }

    @Test
    void warnsAndSkipsStubsWhenTheStubSourceHasNotRunYet() throws Exception {
        writeTemplate("demo-Section.adoc.ftl");
        configure();

        Properties properties = new Properties();
        properties.setProperty(TemplateReportPlugin.PROP_STUB_SOURCE, "demo:NotYetSeen");

        plugin.setResult(result(concept("demo:Section", "bundle", properties), row("core", "ACTIVE")));

        assertThat(new File(reportRoot, "asciidoc/core/demo_Section.adoc")).exists();
        assertThat(new File(reportRoot, "asciidoc").list()).containsExactly("core");
    }

    @Test
    void ignoresRulesThatShipNoTemplate() throws Exception {
        configure();

        assertThatCode(() -> plugin.setResult(result(concept("demo:NoTemplate", null, new Properties()),
            row("core", "ACTIVE")))).doesNotThrowAnyException();

        assertThat(new File(reportRoot, "asciidoc")).doesNotExist();
        assertThat(reportContext.registered).isEmpty();
    }

    @Test
    void honoursPerRuleOverridesOfOutputSubdirectoryAndSuffix() throws Exception {
        writeTemplate("demo-Thing.adoc.ftl");
        configure();

        Properties properties = new Properties();
        properties.setProperty(TemplateReportPlugin.PROP_OUTPUT_SUBDIR, "fragments");
        properties.setProperty(TemplateReportPlugin.PROP_OUTPUT_SUFFIX, ".txt");

        plugin.setResult(result(concept("demo:Thing", null, properties), row("core", "ACTIVE")));

        assertThat(new File(reportRoot, "fragments/demo_Thing.txt")).exists();
        assertThat(new File(reportRoot, "asciidoc")).doesNotExist();
    }

    @Test
    void rendersAnEmptyTableWhenTheRuleProducedNoRows() throws Exception {
        writeTemplate("demo-Thing.adoc.ftl");
        configure();

        plugin.setResult(result(concept("demo:Thing", null, new Properties())));

        assertThat(contentOf(new File(reportRoot, "asciidoc/demo_Thing.adoc"))).contains("rows=0");
    }

    private void configure() throws ReportException {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(TemplateReportPlugin.PROP_TEMPLATE_DIR, templateDir.toAbsolutePath().toString());
        plugin.configure(reportContext, properties);
    }

    private void writeTemplate(String name) throws IOException {
        Files.write(templateDir.resolve(name), TEMPLATE.getBytes(StandardCharsets.UTF_8));
    }

    private static Concept concept(String id, String primaryColumn, Properties reportProperties) {
        Report report = Report.builder()
            .primaryColumn(primaryColumn)
            .properties(reportProperties)
            .build();
        return Concept.builder()
            .id(id)
            .description("description of " + id)
            .severity(Severity.MINOR)
            .report(report)
            .build();
    }

    private static Result<Concept> result(Concept concept, Row... rows) {
        return Result.<Concept>builder()
            .rule(concept)
            .status(Result.Status.SUCCESS)
            .severity(Severity.MINOR)
            .columnNames(Arrays.asList("bundle", "state"))
            .rows(new ArrayList<>(Arrays.asList(rows)))
            .build();
    }

    private static Row row(String bundle, String state) {
        Map<String, Column<?>> columns = new LinkedHashMap<>();
        columns.put("bundle", Column.builder().value(bundle).label(bundle).build());
        columns.put("state", Column.builder().value(state).label(state).build());
        return Row.builder().key(bundle + "/" + state).columns(columns).build();
    }

    private static String contentOf(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
