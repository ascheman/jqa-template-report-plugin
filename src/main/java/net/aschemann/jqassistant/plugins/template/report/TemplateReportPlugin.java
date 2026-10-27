package net.aschemann.jqassistant.plugins.template.report;

import com.buschmais.jqassistant.core.report.api.ReportContext;
import com.buschmais.jqassistant.core.report.api.ReportException;
import com.buschmais.jqassistant.core.report.api.ReportHelper;
import com.buschmais.jqassistant.core.report.api.ReportPlugin;
import com.buschmais.jqassistant.core.report.api.model.Result;
import com.buschmais.jqassistant.core.report.api.model.Row;
import com.buschmais.jqassistant.core.rule.api.model.ExecutableRule;
import com.buschmais.jqassistant.core.rule.api.model.Report;

import freemarker.cache.ClassTemplateLoader;
import freemarker.cache.FileTemplateLoader;
import freemarker.cache.MultiTemplateLoader;
import freemarker.cache.TemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * jQAssistant {@link ReportPlugin} that renders a rule's result through a FreeMarker template,
 * registered under the report id {@code template}.
 *
 * <h2>Template lookup</h2>
 *
 * For a rule with id {@code group:RuleName}, the plugin loads the template named
 * {@code group-RuleName<template-suffix>} (default suffix {@code .adoc.ftl}). Lookup is
 * filesystem-first, classpath-fallback:
 *
 * <ol>
 *   <li>Filesystem: under {@code <template.template-dir>/} (default
 *       {@code jqassistant/templates}, resolved against the JVM working directory).</li>
 *   <li>Classpath: under {@code /templates/} on the thread context classloader (which
 *       under jQA's plugin loader sees every loaded plugin's resources).</li>
 * </ol>
 *
 * If a template exists in both, the filesystem copy wins — handy for overriding a
 * plugin-shipped template without rebuilding the JAR. If no resource exists, the rule
 * is silently skipped (only a debug log is emitted), so a rule set can declare
 * {@code template} as a non-default report type without forcing every rule to ship a
 * template. Set {@code template.template-dir} to an explicit empty string to disable
 * the filesystem layer entirely.
 *
 * <h2>Output layout</h2>
 *
 * Output is written under {@code <reportDir>/<output-subdir>/} (subdir defaults to
 * {@code asciidoc}; configurable). Two modes:
 *
 * <ul>
 *   <li><b>Single-file mode</b> — when the rule's {@code <report>} element does not set
 *   {@code primaryColumn}: a single file at
 *   {@code <reportDir>/<output-subdir>/<rule><output-suffix>} containing all rows.</li>
 *   <li><b>Per-group (per-key) mode</b> — when {@code primaryColumn} is set: rows are grouped
 *   by that column's value and each group is written to
 *   {@code <reportDir>/<output-subdir>/<keyValue>/<rule><output-suffix>}. Filesystem-unsafe
 *   characters in the key are replaced with {@code _}.</li>
 * </ul>
 *
 * <h2>Data model exposed to templates</h2>
 *
 * <pre>
 *   rule    -&gt; { id, description, severity }
 *   columns -&gt; List&lt;String&gt; of column names
 *   rows    -&gt; List&lt;Map&lt;String, String&gt;&gt; of column-name to label-string
 *   key     -&gt; String, only set in per-group mode
 * </pre>
 *
 * Templates should branch on {@code rows?size == 0} to render a meaningful "N/A"
 * line in the empty case (otherwise readers see a section heading followed by an
 * empty table, which is confusing).
 *
 * <h2>Stub rendering: {@code template.stub-source}</h2>
 *
 * Names a "universe-source" rule whose primary-column values define the full set of expected
 * keys. Other rules then render an empty stub (template invoked with {@code rows = []}) for
 * any expected key they did not produce a row for. This keeps consumer-side AsciiDoc
 * {@code include::} directives uniform across the universe — every key has every section's
 * fragment, even if some are stubs.
 *
 * <p><b>Mental model</b> — read this when in doubt:
 *
 * <ol>
 *   <li>The universe of keys is <em>not</em> read from the graph; it is whatever the named
 *   universe-source rule produced as primary-column values during this analyze invocation.</li>
 *   <li>Cypher queries do not consult each other. Cross-rule communication happens entirely
 *   through this plugin's in-memory {@link #seenKeysByRule} map: as each rule's result reaches
 *   {@link #setResult(Result)}, the plugin records the keys that survived the filter; later
 *   rules look them up.</li>
 *   <li>Therefore the named universe-source rule must run <em>before</em> any rule that uses
 *   it as stub source. Enforce with {@code <requiresConcept refId="..."/>} in the rule XML.
 *   If a rule references a stub source that has not been processed yet, a {@code WARN} is
 *   logged and stubs are skipped for that invocation.</li>
 *   <li>A rule using itself as the stub source is a no-op (skipped); by construction every
 *   key in the universe trivially has a row in the rule that defined the universe.</li>
 *   <li>The {@code primary-key-pattern} filter is applied <em>before</em> keys enter the
 *   universe. The universe is the filtered key set, not the raw graph state.</li>
 * </ol>
 *
 * <h2>Event order in a single analyze invocation</h2>
 *
 * <pre>
 *   1. jQA executes ruleA (the universe-source, ordered first via &lt;requiresConcept&gt;)
 *   2. ruleA's Result is handed to TemplateReportPlugin.setResult(...)
 *   3. plugin renders one fragment per row + records seenKeysByRule[ruleA.id] = {observed keys}
 *   4. jQA executes ruleB (a section that uses ruleA as stub source)
 *   5. plugin groups ruleB's rows by primaryColumn; renders real fragments for each group
 *   6. plugin reads template.stub-source on ruleB → ruleA.id
 *   7. plugin computes (seenKeysByRule[ruleA.id] - ruleB's groups) and renders an empty stub
 *      (rows = [], key = ...) for each missing key, using ruleB's template
 *   8. ... repeat for every subsequent rule
 * </pre>
 *
 * <h2>Configuration property reference</h2>
 *
 * <p>Each property below can be set globally in {@code .jqassistant.yml} under
 * {@code jqassistant.analyze.report.properties.*}, or per-rule via
 * {@code <report><properties><property name="..." value="..."/></properties></report>}.
 * Per-rule values override the global value. Output-format properties (subdir, suffixes,
 * label) fall back to the global value if the per-rule value is missing or empty.
 * The filter and stub-source treat an explicit empty string as "disable for this rule".</p>
 *
 * <table>
 *   <caption>Configuration properties</caption>
 *   <tr><th>Key</th><th>Type</th><th>Default</th><th>Effect</th></tr>
 *   <tr><td>{@code template.output-subdir}</td><td>string</td><td>{@code asciidoc}</td>
 *       <td>Subdirectory under {@code <reportDir>/} where rendered files land.</td></tr>
 *   <tr><td>{@code template.output-suffix}</td><td>string</td><td>{@code .adoc}</td>
 *       <td>Filename suffix for rendered files (include the leading dot).</td></tr>
 *   <tr><td>{@code template.template-suffix}</td><td>string</td><td>{@code .adoc.ftl}</td>
 *       <td>Suffix appended to the rule id when looking up the template on the classpath.</td></tr>
 *   <tr><td>{@code template.report-label}</td><td>string</td><td>{@code AsciiDoc}</td>
 *       <td>Human-readable label registered with jQAssistant's report context (shown in the master report listing).</td></tr>
 *   <tr><td>{@code template.primary-key-pattern}</td><td>regex</td><td>(none)</td>
 *       <td>In per-group mode, drops groups whose primary-column value does not match.</td></tr>
 *   <tr><td>{@code template.stub-source}</td><td>rule id</td><td>(none)</td>
 *       <td>Renders empty-row stubs for keys present in the named rule but missing here.</td></tr>
 *   <tr><td>{@code template.template-dir}</td><td>path</td><td>{@code jqassistant/templates}</td>
 *       <td>Filesystem directory checked before the classpath. Resolved against the JVM
 *       working directory. Explicit empty string disables the filesystem layer.</td></tr>
 * </table>
 *
 * <h2>Caveat: jQAssistant config ordinals</h2>
 *
 * Properties from {@code .jqassistant.yml} (ordinal 100) override Maven {@code -D} system
 * properties (ordinal 90). To change a value temporarily, edit the YAML or pass it via the
 * jQAssistant Maven plugin's {@code <yaml>} parameter; CLI {@code -D} won't override.
 */
public class TemplateReportPlugin implements ReportPlugin {

    public static final String REPORT_TYPE = "template";

    /** See class-level javadoc. */
    public static final String PROP_OUTPUT_SUBDIR = "template.output-subdir";

    /** See class-level javadoc. */
    public static final String PROP_OUTPUT_SUFFIX = "template.output-suffix";

    /** See class-level javadoc. */
    public static final String PROP_TEMPLATE_SUFFIX = "template.template-suffix";

    /** See class-level javadoc. */
    public static final String PROP_REPORT_LABEL = "template.report-label";

    /** See class-level javadoc. */
    public static final String PROP_PRIMARY_KEY_PATTERN = "template.primary-key-pattern";

    /** See class-level javadoc. */
    public static final String PROP_STUB_SOURCE = "template.stub-source";

    /** See class-level javadoc. */
    public static final String PROP_TEMPLATE_DIR = "template.template-dir";

    private static final Logger LOGGER = LoggerFactory.getLogger(TemplateReportPlugin.class);

    private static final String TEMPLATE_BASE = "/templates";

    private static final String DEFAULT_OUTPUT_SUBDIR = "asciidoc";

    private static final String DEFAULT_OUTPUT_SUFFIX = ".adoc";

    private static final String DEFAULT_TEMPLATE_SUFFIX = ".adoc.ftl";

    private static final String DEFAULT_REPORT_LABEL = "AsciiDoc";

    private static final String DEFAULT_TEMPLATE_DIR = "jqassistant/templates";

    private ReportContext reportContext;

    private Configuration freemarker;

    private String globalOutputSubdir;

    private String globalOutputSuffix;

    private String globalTemplateSuffix;

    private String globalReportLabel;

    private Pattern globalPrimaryKeyPattern;

    private String globalStubSource;

    /**
     * Per-rule keys observed during this analyze run. Read by other rules
     * (via {@code template.stub-source}) to render empty stubs for missing keys.
     * Concurrent map for thread-safety, though jQA analyze typically runs single-threaded.
     */
    private final Map<String, Set<String>> seenKeysByRule = new ConcurrentHashMap<>();

    /**
     * Resolved per-rule render settings. Built once per {@link #setResult(Result)} call so the
     * downstream render methods don't need a long argument list.
     */
    private static final class RuleRenderContext {
        final Result<? extends ExecutableRule> result;
        final ExecutableRule<?> rule;
        final Template template;
        final File baseDir;
        final String outputSuffix;
        final String reportLabel;

        RuleRenderContext(Result<? extends ExecutableRule> result, Template template, File baseDir, String outputSuffix, String reportLabel) {
            this.result = result;
            this.rule = result.getRule();
            this.template = template;
            this.baseDir = baseDir;
            this.outputSuffix = outputSuffix;
            this.reportLabel = reportLabel;
        }
    }

    @Override
    public void configure(ReportContext reportContext, Map<String, Object> properties) throws ReportException {
        this.reportContext = reportContext;
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_32);
        cfg.setTemplateLoader(buildTemplateLoader(properties));
        cfg.setDefaultEncoding(StandardCharsets.UTF_8.name());
        cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        cfg.setLogTemplateExceptions(false);
        cfg.setWrapUncheckedExceptions(true);
        cfg.setFallbackOnNullLoopVariable(false);
        this.freemarker = cfg;

        this.globalOutputSubdir   = stringProperty(properties, PROP_OUTPUT_SUBDIR,   DEFAULT_OUTPUT_SUBDIR);
        this.globalOutputSuffix   = stringProperty(properties, PROP_OUTPUT_SUFFIX,   DEFAULT_OUTPUT_SUFFIX);
        this.globalTemplateSuffix = stringProperty(properties, PROP_TEMPLATE_SUFFIX, DEFAULT_TEMPLATE_SUFFIX);
        this.globalReportLabel    = stringProperty(properties, PROP_REPORT_LABEL,    DEFAULT_REPORT_LABEL);
        LOGGER.info("Template report: output {}/...{}, template suffix {}, label '{}'",
            globalOutputSubdir, globalOutputSuffix, globalTemplateSuffix, globalReportLabel);

        Object globalPattern = properties != null ? properties.get(PROP_PRIMARY_KEY_PATTERN) : null;
        this.globalPrimaryKeyPattern = compilePattern(globalPattern, "global plugin properties");
        if (globalPrimaryKeyPattern != null) {
            LOGGER.info("Template report: global primary-key-pattern = '{}'", globalPrimaryKeyPattern.pattern());
        }

        Object globalStub = properties != null ? properties.get(PROP_STUB_SOURCE) : null;
        this.globalStubSource = globalStub != null && !globalStub.toString().trim().isEmpty() ? globalStub.toString().trim() : null;
        if (globalStubSource != null) {
            LOGGER.info("Template report: global stub-source = '{}'", globalStubSource);
        }
    }

    @Override
    public void setResult(Result<? extends ExecutableRule> result) throws ReportException {
        ExecutableRule<?> rule = result.getRule();
        String ruleId = rule.getId();

        String templateSuffix = resolveString(rule, PROP_TEMPLATE_SUFFIX, globalTemplateSuffix);
        String outputSuffix   = resolveString(rule, PROP_OUTPUT_SUFFIX,   globalOutputSuffix);
        String outputSubdir   = resolveString(rule, PROP_OUTPUT_SUBDIR,   globalOutputSubdir);
        String reportLabel    = resolveString(rule, PROP_REPORT_LABEL,    globalReportLabel);

        String templateName = ruleIdToTemplateName(ruleId, templateSuffix);
        Template template;
        try {
            template = freemarker.getTemplate(templateName);
        } catch (IOException e) {
            LOGGER.debug("No template '{}' for rule '{}' (checked filesystem dir and classpath {}/), skipping report.", templateName, ruleId, TEMPLATE_BASE);
            return;
        }

        File baseDir = reportContext.getReportDirectory(outputSubdir);
        RuleRenderContext ctx = new RuleRenderContext(result, template, baseDir, outputSuffix, reportLabel);

        String primaryColumn = primaryColumnOf(rule);
        if (primaryColumn == null || primaryColumn.isEmpty()) {
            renderSingleFile(ctx);
        } else {
            renderPerGroup(ctx, primaryColumn);
        }
    }

    private void renderSingleFile(RuleRenderContext ctx) throws ReportException {
        File outFile = new File(ctx.baseDir, ReportHelper.escapeRuleId(ctx.rule) + ctx.outputSuffix);

        Map<String, Object> dataModel = baseDataModel(ctx.result);
        dataModel.put("rows", allRowsAsMaps(ctx.result));

        renderAndRegister(ctx, dataModel, outFile);
    }

    private void renderPerGroup(RuleRenderContext ctx, String primaryColumn) throws ReportException {
        Pattern keyPattern = resolvePrimaryKeyPattern(ctx.rule);
        Map<String, List<Map<String, Object>>> groups = groupByPrimaryColumn(ctx.result, primaryColumn, keyPattern);

        // Record this rule's observed keys so later rules with template.stub-source pointing here can render stubs.
        seenKeysByRule.put(ctx.rule.getId(), new LinkedHashSet<>(groups.keySet()));

        for (Map.Entry<String, List<Map<String, Object>>> entry : groups.entrySet()) {
            renderOneGroup(ctx, entry.getKey(), entry.getValue());
        }

        // Stub rendering: empty fragments for any expected key this rule did not produce a row for.
        String stubSource = resolveStubSource(ctx.rule);
        if (stubSource != null && !stubSource.equals(ctx.rule.getId())) {
            Set<String> universe = seenKeysByRule.get(stubSource);
            if (universe == null) {
                LOGGER.warn("Rule '{}' configured template.stub-source='{}', but that rule has not been processed yet (forgot a <requiresConcept>?). Skipping stubs.",
                    ctx.rule.getId(), stubSource);
            } else {
                int stubCount = 0;
                for (String key : universe) {
                    if (!groups.containsKey(key)) {
                        renderOneGroup(ctx, key, Collections.emptyList());
                        stubCount++;
                    }
                }
                if (stubCount > 0) {
                    LOGGER.info("Rule '{}': rendered {} empty stubs for keys present in '{}' but missing here.", ctx.rule.getId(), stubCount, stubSource);
                }
            }
        }

        if (groups.isEmpty() && stubSource == null) {
            if (keyPattern != null) {
                LOGGER.info("Rule '{}': no rows matched primary-key-pattern '{}', nothing to render.", ctx.rule.getId(), keyPattern.pattern());
            } else {
                LOGGER.info("No rows for rule '{}'; nothing to render.", ctx.rule.getId());
            }
        }
    }

    private void renderOneGroup(RuleRenderContext ctx, String key, List<Map<String, Object>> rows) throws ReportException {
        File groupDir = new File(ctx.baseDir, sanitize(key));
        if (!groupDir.exists() && !groupDir.mkdirs()) {
            throw new ReportException("Cannot create report directory '" + groupDir + "'");
        }
        File outFile = new File(groupDir, ReportHelper.escapeRuleId(ctx.rule) + ctx.outputSuffix);

        Map<String, Object> dataModel = baseDataModel(ctx.result);
        dataModel.put("key", key);
        dataModel.put("rows", rows);

        renderAndRegister(ctx, dataModel, outFile);
    }

    private void renderAndRegister(RuleRenderContext ctx, Map<String, Object> dataModel, File outFile) throws ReportException {
        try (Writer writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8))) {
            ctx.template.process(dataModel, writer);
        } catch (IOException | TemplateException e) {
            throw new ReportException("Failed to render template for rule '" + ctx.rule.getId() + "'", e);
        }

        LOGGER.info("Rendered {} report for '{}' to {}", ctx.reportLabel, ctx.rule.getId(), outFile);
        try {
            reportContext.addReport(ctx.reportLabel, ctx.rule, ReportContext.ReportType.LINK, outFile.toURI().toURL());
        } catch (MalformedURLException e) {
            throw new ReportException("Cannot register report file '" + outFile + "' for rule '" + ctx.rule.getId() + "'", e);
        }
    }

    private static String primaryColumnOf(ExecutableRule<?> rule) {
        Report report = rule.getReport();
        return report != null ? report.getPrimaryColumn() : null;
    }

    private static Map<String, Object> baseDataModel(Result<? extends ExecutableRule> result) {
        Map<String, Object> model = new LinkedHashMap<>();

        ExecutableRule<?> rule = result.getRule();
        Map<String, Object> ruleModel = new LinkedHashMap<>();
        ruleModel.put("id", rule.getId());
        ruleModel.put("description", rule.getDescription());
        ruleModel.put("severity", rule.getSeverity() != null ? rule.getSeverity().getValue() : null);
        model.put("rule", ruleModel);

        List<String> columnNames = result.getColumnNames();
        model.put("columns", columnNames != null ? columnNames : Collections.emptyList());
        return model;
    }

    private static List<Map<String, Object>> allRowsAsMaps(Result<? extends ExecutableRule> result) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (result.getRows() != null) {
            for (Row row : result.getRows()) {
                rows.add(toRowMap(row));
            }
        }
        return rows;
    }

    private static Map<String, List<Map<String, Object>>> groupByPrimaryColumn(Result<? extends ExecutableRule> result, String primaryColumn, Pattern keyPattern) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        if (result.getRows() == null) {
            return groups;
        }
        for (Row row : result.getRows()) {
            Map<String, Object> rowMap = toRowMap(row);
            Object keyValue = rowMap.get(primaryColumn);
            String key = keyValue != null ? keyValue.toString() : "_unset";
            if (keyPattern != null && !keyPattern.matcher(key).matches()) {
                continue;
            }
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(rowMap);
        }
        return groups;
    }

    private Pattern resolvePrimaryKeyPattern(ExecutableRule<?> rule) {
        Report report = rule.getReport();
        if (report != null && report.getProperties() != null) {
            Object override = report.getProperties().get(PROP_PRIMARY_KEY_PATTERN);
            Pattern perRule = compilePattern(override, "rule '" + rule.getId() + "' report properties");
            if (perRule != null) {
                return perRule;
            }
        }
        return globalPrimaryKeyPattern;
    }

    private String resolveStubSource(ExecutableRule<?> rule) {
        Report report = rule.getReport();
        if (report != null && report.getProperties() != null) {
            Object override = report.getProperties().get(PROP_STUB_SOURCE);
            if (override != null) {
                String s = override.toString().trim();
                if (!s.isEmpty()) {
                    return s;
                }
                return null; // explicit empty disables stubs even when global is set
            }
        }
        return globalStubSource;
    }

    /**
     * Resolves a string property: per-rule value (if non-empty) wins, otherwise the global
     * value. Empty per-rule values fall through to the global value (rather than disabling
     * the feature) because output-format properties have no meaningful "disabled" state.
     */
    private static String resolveString(ExecutableRule<?> rule, String propKey, String globalValue) {
        Report report = rule.getReport();
        if (report != null && report.getProperties() != null) {
            Object override = report.getProperties().get(propKey);
            if (override != null) {
                String s = override.toString().trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return globalValue;
    }

    /**
     * Builds the FreeMarker template loader: optional filesystem layer (from
     * {@code template.template-dir}, default {@code jqassistant/templates}) chained
     * with the classpath layer at {@code /templates}. Filesystem wins when both have
     * a hit, which lets users override a plugin-shipped template without rebuilding
     * the JAR.
     *
     * <ul>
     *   <li>Property absent → default dir; if it doesn't exist, classpath only (DEBUG log).</li>
     *   <li>Property set to a non-empty path → resolved against the JVM working directory;
     *       if the directory is missing the filesystem layer is skipped (WARN log).</li>
     *   <li>Property set to an explicit empty string → filesystem loader disabled, classpath only.</li>
     * </ul>
     */
    private static TemplateLoader buildTemplateLoader(Map<String, Object> properties) {
        ClassTemplateLoader classpathLoader = new ClassTemplateLoader(
            Thread.currentThread().getContextClassLoader(), TEMPLATE_BASE);

        Object raw = properties != null ? properties.get(PROP_TEMPLATE_DIR) : null;
        if (raw != null && raw.toString().trim().isEmpty()) {
            LOGGER.info("Template report: filesystem template loader disabled by explicit empty '{}'.", PROP_TEMPLATE_DIR);
            return classpathLoader;
        }

        String dirPath = raw != null ? raw.toString().trim() : DEFAULT_TEMPLATE_DIR;
        File dir = new File(dirPath).getAbsoluteFile();
        if (!dir.isDirectory()) {
            if (raw != null) {
                LOGGER.warn("Template report: configured template dir '{}' does not exist, falling back to classpath /templates only.", dir);
            } else {
                LOGGER.debug("Template report: default template dir '{}' not present, classpath /templates only.", dir);
            }
            return classpathLoader;
        }

        try {
            FileTemplateLoader fileLoader = new FileTemplateLoader(dir);
            LOGGER.info("Template report: filesystem template dir '{}' active (precedence over classpath {}).", dir, TEMPLATE_BASE);
            return new MultiTemplateLoader(new TemplateLoader[]{fileLoader, classpathLoader});
        } catch (IOException e) {
            LOGGER.warn("Template report: cannot initialise filesystem template loader for '{}', classpath only ({}).", dir, e.getMessage());
            return classpathLoader;
        }
    }

    private static String stringProperty(Map<String, Object> properties, String key, String defaultValue) {
        if (properties == null) {
            return defaultValue;
        }
        Object value = properties.get(key);
        if (value == null) {
            return defaultValue;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? defaultValue : s;
    }

    private static Map<String, Object> toRowMap(Row row) {
        Map<String, Object> rowMap = new LinkedHashMap<>();
        row.getColumns().forEach((name, col) -> rowMap.put(name, col != null ? col.getLabel() : null));
        return rowMap;
    }

    static String ruleIdToTemplateName(String ruleId, String templateSuffix) {
        return ruleId.replace(':', '-') + templateSuffix;
    }

    static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return "_unset";
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static Pattern compilePattern(Object raw, String source) {
        if (raw == null) {
            return null;
        }
        String s = raw.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Pattern.compile(s);
        } catch (PatternSyntaxException e) {
            LOGGER.warn("Ignoring invalid regex '{}' from {} ({}).", s, source, e.getDescription());
            return null;
        }
    }
}
