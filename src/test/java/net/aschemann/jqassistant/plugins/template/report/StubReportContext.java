package net.aschemann.jqassistant.plugins.template.report;

import com.buschmais.jqassistant.core.report.api.ReportContext;
import com.buschmais.jqassistant.core.report.api.ReportException;
import com.buschmais.jqassistant.core.report.api.configuration.Build;
import com.buschmais.jqassistant.core.rule.api.model.ExecutableRule;
import com.buschmais.jqassistant.core.store.api.Store;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal {@link ReportContext} for plugin tests: hands out directories below a temporary
 * root and records what the plugin registered. Only the methods the plugin actually calls
 * are implemented; the rest fail loudly rather than returning something plausible, so a
 * future plugin change that starts using them cannot pass unnoticed.
 */
class StubReportContext implements ReportContext {

    /** One entry per {@link #addReport} call, in call order. */
    final List<Registered> registered = new ArrayList<>();

    private final File reportRoot;

    StubReportContext(File reportRoot) {
        this.reportRoot = reportRoot;
    }

    static final class Registered {
        final String label;
        final String ruleId;
        final URL url;

        Registered(String label, String ruleId, URL url) {
            this.label = label;
            this.ruleId = ruleId;
            this.url = url;
        }
    }

    @Override
    public File getReportDirectory(String subdirectory) {
        File directory = new File(reportRoot, subdirectory);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create report directory " + directory);
        }
        return directory;
    }

    @Override
    public File getOutputDirectory() {
        return reportRoot;
    }

    @Override
    public <E extends ExecutableRule<?>> Report<E> addReport(String label, E rule, ReportType reportType, URL url) {
        registered.add(new Registered(label, rule.getId(), url));
        return null;
    }

    @Override
    public <E extends ExecutableRule<?>> List<Report<?>> getReports(E rule) {
        throw new UnsupportedOperationException("not used by TemplateReportPlugin");
    }

    @Override
    public Build getBuild() {
        throw new UnsupportedOperationException("not used by TemplateReportPlugin");
    }

    @Override
    public ClassLoader getClassLoader() {
        throw new UnsupportedOperationException("not used by TemplateReportPlugin");
    }

    @Override
    public Store getStore() {
        throw new UnsupportedOperationException("not used by TemplateReportPlugin");
    }

    @Override
    public File createReportArchive() throws ReportException {
        throw new UnsupportedOperationException("not used by TemplateReportPlugin");
    }
}
