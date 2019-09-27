package plugins;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map.Entry;
import java.util.function.Consumer;

import org.junit.Test;

import com.google.inject.Inject;

import org.jenkinsci.test.acceptance.docker.DockerContainerHolder;
import org.jenkinsci.test.acceptance.docker.fixtures.JavaGitContainer;
import org.jenkinsci.test.acceptance.junit.AbstractJUnitTest;
import org.jenkinsci.test.acceptance.junit.WithCredentials;
import org.jenkinsci.test.acceptance.junit.WithDocker;
import org.jenkinsci.test.acceptance.junit.WithPlugins;
import org.jenkinsci.test.acceptance.plugins.maven.MavenInstallation;
import org.jenkinsci.test.acceptance.plugins.maven.MavenModuleSet;
import org.jenkinsci.test.acceptance.plugins.ssh_slaves.SshSlaveLauncher;
import org.jenkinsci.test.acceptance.plugins.warnings_ng.AnalysisResult;
import org.jenkinsci.test.acceptance.plugins.warnings_ng.AnalysisResult.Tab;
import org.jenkinsci.test.acceptance.plugins.warnings_ng.AnalysisSummary;
import org.jenkinsci.test.acceptance.plugins.warnings_ng.AnalysisSummary.InfoType;
import org.jenkinsci.test.acceptance.plugins.warnings_ng.ConsoleLogView;
import org.jenkinsci.test.acceptance.plugins.warnings_ng.DefaultWarningsTableRow;
import org.jenkinsci.test.acceptance.plugins.warnings_ng.DetailsTableRow;
import org.jenkinsci.test.acceptance.plugins.warnings_ng.DryIssuesTableRow;
import org.jenkinsci.test.acceptance.plugins.warnings_ng.InfoView;
import org.jenkinsci.test.acceptance.plugins.warnings_ng.IssuesRecorder;
import org.jenkinsci.test.acceptance.plugins.warnings_ng.IssuesRecorder.QualityGateBuildResult;
import org.jenkinsci.test.acceptance.plugins.warnings_ng.IssuesRecorder.QualityGateType;
import org.jenkinsci.test.acceptance.plugins.warnings_ng.IssuesRecorder.StaticAnalysisTool;
import org.jenkinsci.test.acceptance.plugins.warnings_ng.IssuesTable;
import org.jenkinsci.test.acceptance.plugins.warnings_ng.ScrollerUtil;
import org.jenkinsci.test.acceptance.plugins.warnings_ng.SourceView;
import org.jenkinsci.test.acceptance.po.Build;
import org.jenkinsci.test.acceptance.po.DumbSlave;
import org.jenkinsci.test.acceptance.po.FreeStyleJob;
import org.jenkinsci.test.acceptance.po.Job;
import org.jenkinsci.test.acceptance.po.Slave;
import org.jenkinsci.test.acceptance.po.WorkflowJob;

import static org.jenkinsci.test.acceptance.plugins.warnings_ng.Assertions.*;

/**
 * Acceptance tests for the Warnings Next Generation Plugin.
 *
 * @author Frank Christian Geyer
 * @author Ullrich Hafner
 * @author Manuel Hampp
 * @author Anna-Maria Hardi
 * @author Elvira Hauer
 * @author Deniz Mardin
 * @author Stephan Plöderl
 * @author Alexander Praegla
 * @author Michaela Reitschuster
 * @author Arne Schöntag
 * @author Alexandra Wenzel
 * @author Nikolai Wohlgemuth
 * @author Florian Hageneder
 * @author Veronika Zwickenpflug
 */
@WithPlugins("warnings-ng")
public class WarningsNextGenerationPluginTest extends AbstractJUnitTest {
    private static final String WARNINGS_PLUGIN_PREFIX = "/warnings_ng_plugin/";

    private static final String CHECKSTYLE_ID = "checkstyle";
    private static final String ANALYSIS_ID = "analysis";
    private static final String CPD_ID = "cpd";
    private static final String PMD_ID = "pmd";
    private static final String FINDBUGS_ID = "findbugs";
    private static final String MAVEN_ID = "maven-warnings";

    private static final String WARNING_LOW_PRIORITY = "Low";

    private static final String CHECKSTYLE_XML = "checkstyle-result.xml";
    private static final String SOURCE_VIEW_FOLDER = WARNINGS_PLUGIN_PREFIX + "source-view/";

    private static final String CPD_REPORT = "duplicate_code/cpd.xml";
    private static final String CPD_SOURCE_NAME = "Main.java";
    private static final String CPD_SOURCE_PATH = "duplicate_code/Main.java";

    private static final String NO_PACKAGE = "-";

    /**
     * Credentials to access the docker container. The credentials are stored with the specified ID and use the provided
     * SSH key. Use the following annotation on your test case to use the specified docker container as git server or
     * build agent:
     * <blockquote>
     * <pre>@Test @WithDocker @WithCredentials(credentialType = WithCredentials.SSH_USERNAME_PRIVATE_KEY,
     *                                    values = {CREDENTIALS_ID, CREDENTIALS_KEY})}
     * public void shouldTestWithDocker() {
     * }
     * </pre></blockquote>
     */
    private static final String CREDENTIALS_ID = "git";
    private static final String CREDENTIALS_KEY = "/org/jenkinsci/test/acceptance/docker/fixtures/GitContainer/unsafe";

    @Inject
    private DockerContainerHolder<JavaGitContainer> dockerContainer;

    /**
     * Runs a pipeline with all tools two times. Verifies the analysis results in several views. Additionally,
     * verifies the expansion of tokens with the token-macro plugin.
     */
    @Test
    @WithPlugins({"token-macro", "workflow-cps", "pipeline-stage-step", "workflow-durable-task-step", "workflow-basic-steps"})
    public void should_record_issues_in_pipeline_and_expand_tokens() {
        WorkflowJob job = jenkins.jobs.create(WorkflowJob.class);
        job.sandbox.check();

        createRecordIssuesStep(job, 1);

        job.save();

        Build referenceBuild = buildJob(job);

        assertThat(referenceBuild.getConsole()).contains("[total=4]");
        assertThat(referenceBuild.getConsole()).contains("[new=0]");
        assertThat(referenceBuild.getConsole()).contains("[fixed=0]");
        assertThat(referenceBuild.getConsole()).contains("[checkstyle=1]");
        assertThat(referenceBuild.getConsole()).contains("[pmd=3]");

        job.configure(() -> createRecordIssuesStep(job, 2));

        Build build = buildJob(job);

        assertThat(build.getConsole()).contains("[total=25]");
        assertThat(build.getConsole()).contains("[new=23]");
        assertThat(build.getConsole()).contains("[fixed=2]");
        assertThat(build.getConsole()).contains("[checkstyle=3]");
        assertThat(build.getConsole()).contains("[pmd=2]");

        verifyPmd(build);
        verifyFindBugs(build);
        verifyCheckStyle(build);
        verifyCpd(build);
    }

    private void createRecordIssuesStep(final WorkflowJob job, final int build) {
        String[] fileNames = {"checkstyle-result.xml", "pmd.xml", "findbugsXml.xml", "cpd.xml", "Main.java"};
        StringBuilder resourceCopySteps = new StringBuilder();
        for (String fileName : fileNames) {
            resourceCopySteps.append(job.copyResourceStep(WARNINGS_PLUGIN_PREFIX
                    + "build_status_test/build_0" + build + "/" + fileName).replace("\\", "\\\\"));
        }
        job.script.set("node {\n"
                + resourceCopySteps.toString()
                + "recordIssues tool: checkStyle(pattern: '**/checkstyle*')\n"
                + "recordIssues tool: pmdParser(pattern: '**/pmd*')\n"
                + "recordIssues tools: [cpd(pattern: '**/cpd*', highThreshold:8, normalThreshold:3), findBugs()], aggregatingResults: 'false' \n"
                + "def total = tm('${ANALYSIS_ISSUES_COUNT}')\n"
                + "echo '[total=' + total + ']' \n"
                + "def checkstyle = tm('${ANALYSIS_ISSUES_COUNT, tool=\"checkstyle\"}')\n"
                + "echo '[checkstyle=' + checkstyle + ']' \n"
                + "def pmd = tm('${ANALYSIS_ISSUES_COUNT, tool=\"pmd\"}')\n"
                + "echo '[pmd=' + pmd + ']' \n"
                + "def newSize = tm('${ANALYSIS_ISSUES_COUNT, type=\"NEW\"}')\n"
                + "echo '[new=' + newSize + ']' \n"
                + "def fixedSize = tm('${ANALYSIS_ISSUES_COUNT, type=\"FIXED\"}')\n"
                + "echo '[fixed=' + fixedSize + ']' \n"
                + "}");
    }

    /**
     * Tests the build overview page by running two builds with three different tools enabled. Checks the contents of
     * the result summaries for each tool.
     */
    @Test
    public void should_show_build_summary_and_link_to_details() {
        FreeStyleJob job = createFreeStyleJob("build_status_test/build_01");
        addRecorder(job);
        job.save();

        buildJob(job);

        reconfigureJobWithResource(job, "build_status_test/build_02");

        Build build = buildJob(job);

        verifyPmd(build);
        verifyFindBugs(build);
        verifyCheckStyle(build);
        verifyCpd(build);
    }

    private void verifyCpd(final Build build) {
        build.open();
        AnalysisSummary cpd = new AnalysisSummary(build, CPD_ID);
        assertThat(cpd).isDisplayed();
        assertThat(cpd).hasTitleText("CPD: 20 warnings");
        assertThat(cpd).hasNewSize(20);
        assertThat(cpd).hasFixedSize(0);
        assertThat(cpd).hasReferenceBuild(1);
        assertThat(cpd).hasInfoType(InfoType.INFO);

        AnalysisResult cpdDetails = cpd.openOverallResult();
        assertThat(cpdDetails).hasActiveTab(Tab.ISSUES);
        assertThat(cpdDetails).hasOnlyAvailableTabs(Tab.ISSUES);

        IssuesTable issuesTable = cpdDetails.openIssuesTable();
        assertThat(issuesTable).hasSize(10);
        assertThat(issuesTable).hasTotal(20);

        DryIssuesTableRow firstRow = issuesTable.getRowAs(0, DryIssuesTableRow.class);
        DryIssuesTableRow secondRow = issuesTable.getRowAs(1, DryIssuesTableRow.class);

        firstRow.toggleDetailsRow();
        assertThat(issuesTable).hasSize(11);

        DetailsTableRow detailsRow = issuesTable.getRowAs(1, DetailsTableRow.class);
        assertThat(detailsRow).hasDetails("Found duplicated code.\nfunctionOne();");

        assertThat(issuesTable.getRowAs(2, DryIssuesTableRow.class)).isEqualTo(secondRow);

        firstRow.toggleDetailsRow();
        assertThat(issuesTable).hasSize(10);
        assertThat(issuesTable.getRowAs(1, DryIssuesTableRow.class)).isEqualTo(secondRow);

        SourceView sourceView = firstRow.openSourceCode();
        assertThat(sourceView).hasFileName(CPD_SOURCE_NAME);

        String expectedSourceCode = toString(WARNINGS_PLUGIN_PREFIX + CPD_SOURCE_PATH);
        assertThat(sourceView.getSourceCode()).isEqualToIgnoringWhitespace(expectedSourceCode);

        cpdDetails.open();
        issuesTable = cpdDetails.openIssuesTable();
        firstRow = issuesTable.getRowAs(0, DryIssuesTableRow.class);

        AnalysisResult lowSeverity = firstRow.clickOnSeverityLink();
        IssuesTable lowSeverityTable = lowSeverity.openIssuesTable();
        assertThat(lowSeverityTable).hasSize(6);
        assertThat(lowSeverityTable).hasTotal(6);

        for (int i = 0; i < 6; i++) {
            DryIssuesTableRow row = lowSeverityTable.getRowAs(i, DryIssuesTableRow.class);
            assertThat(row).hasSeverity(WARNING_LOW_PRIORITY);
        }

        build.open();
        assertThat(openInfoView(build, CPD_ID))
                .hasNoErrorMessages()
                .hasInfoMessages("-> found 1 file",
                        "-> found 20 issues (skipped 0 duplicates)",
                        "-> 1 copied, 0 not in workspace, 0 not-found, 0 with I/O error",
                        "Issues delta (vs. reference build): outstanding: 0, new: 20, fixed: 0");

    }

    private void verifyFindBugs(final Build build) {
        build.open();
        AnalysisSummary findbugs = new AnalysisSummary(build, FINDBUGS_ID);
        assertThat(findbugs).isDisplayed();
        assertThat(findbugs).hasTitleText("FindBugs: No warnings");
        assertThat(findbugs).hasNewSize(0);
        assertThat(findbugs).hasFixedSize(0);
        assertThat(findbugs).hasReferenceBuild(1);
        assertThat(findbugs).hasInfoType(InfoType.INFO);
        assertThat(findbugs).hasDetails("No warnings for 2 builds, i.e. since build 1");

        build.open();
        assertThat(openInfoView(build, FINDBUGS_ID))
                .hasNoErrorMessages()
                .hasInfoMessages("-> found 1 file",
                        "-> found 0 issues (skipped 0 duplicates)",
                        "Issues delta (vs. reference build): outstanding: 0, new: 0, fixed: 0");
    }

    private void verifyPmd(final Build build) {
        build.open();
        AnalysisSummary pmd = new AnalysisSummary(build, PMD_ID);
        assertThat(pmd).isDisplayed();
        assertThat(pmd).hasTitleText("PMD: 2 warnings");
        assertThat(pmd).hasNewSize(0);
        assertThat(pmd).hasFixedSize(1);
        assertThat(pmd).hasReferenceBuild(1);
        assertThat(pmd).hasInfoType(InfoType.ERROR);

        AnalysisResult pmdDetails = pmd.openOverallResult();
        assertThat(pmdDetails).hasActiveTab(Tab.CATEGORIES);
        assertThat(pmdDetails).hasTotal(2);
        assertThat(pmdDetails).hasOnlyAvailableTabs(Tab.CATEGORIES, Tab.TYPES, Tab.ISSUES);

        build.open();
        assertThat(openInfoView(build, PMD_ID))
                .hasInfoMessages("-> found 1 file",
                        "-> found 2 issues (skipped 0 duplicates)",
                        "Issues delta (vs. reference build): outstanding: 2, new: 0, fixed: 1")
                .hasErrorMessages("Can't resolve absolute paths for some files:",
                        "Can't create fingerprints for some files:");
    }

    private void verifyCheckStyle(final Build build) {
        build.open();
        AnalysisSummary checkstyle = new AnalysisSummary(build, CHECKSTYLE_ID);
        assertThat(checkstyle).isDisplayed();
        assertThat(checkstyle).hasTitleText("CheckStyle: 3 warnings");
        assertThat(checkstyle).hasNewSize(3);
        assertThat(checkstyle).hasFixedSize(1);
        assertThat(checkstyle).hasReferenceBuild(1);
        assertThat(checkstyle).hasInfoType(InfoType.ERROR);

        AnalysisResult checkstyleDetails = checkstyle.openOverallResult();
        assertThat(checkstyleDetails).hasActiveTab(Tab.CATEGORIES);
        assertThat(checkstyleDetails).hasTotal(3);
        assertThat(checkstyleDetails).hasOnlyAvailableTabs(Tab.CATEGORIES, Tab.TYPES, Tab.ISSUES);

        IssuesTable issuesTable = checkstyleDetails.openIssuesTable();
        assertThat(issuesTable).hasSize(3);
        assertThat(issuesTable).hasTotal(3);

        DefaultWarningsTableRow tableRow = issuesTable.getRowAs(0, DefaultWarningsTableRow.class);
        assertThat(tableRow).hasFileName("RemoteLauncher.java");
        assertThat(tableRow).hasLineNumber(59);
        assertThat(tableRow).hasCategory("Checks");
        assertThat(tableRow).hasType("FinalParametersCheck");
        assertThat(tableRow).hasSeverity("Error");
        assertThat(tableRow).hasAge(1);

        build.open();
        assertThat(openInfoView(build, CHECKSTYLE_ID))
                .hasInfoMessages("-> found 1 file",
                        "-> found 3 issues (skipped 0 duplicates)",
                        "Issues delta (vs. reference build): outstanding: 0, new: 3, fixed: 1")
                .hasErrorMessages("Can't resolve absolute paths for some files:",
                        "Can't create fingerprints for some files:");
    }

    private InfoView openInfoView(final Build build, final String toolId) {
        return new AnalysisSummary(build, toolId).openInfoView();
    }

    /**
     * Tests the build overview page by running two builds that aggregate the three different tools into a single
     * result. Checks the contents of the result summary.
     */
    @Test
    public void should_aggregate_tools_into_single_result() {
        FreeStyleJob job = createFreeStyleJob("build_status_test/build_01");
        IssuesRecorder recorder = addRecorder(job);
        recorder.setEnabledForAggregation(true);
        recorder.addQualityGateConfiguration(4, QualityGateType.TOTAL, QualityGateBuildResult.UNSTABLE);
        recorder.addQualityGateConfiguration(3, QualityGateType.NEW, QualityGateBuildResult.FAILED);
        recorder.setIgnoreQualityGate(true);

        job.save();

        Build referenceBuild = buildJob(job).shouldBeUnstable();
        referenceBuild.open();

        assertThat(new AnalysisSummary(referenceBuild, CHECKSTYLE_ID)).isNotDisplayed();
        assertThat(new AnalysisSummary(referenceBuild, PMD_ID)).isNotDisplayed();
        assertThat(new AnalysisSummary(referenceBuild, FINDBUGS_ID)).isNotDisplayed();

        AnalysisSummary referenceSummary = new AnalysisSummary(referenceBuild, ANALYSIS_ID);
        assertThat(referenceSummary).isDisplayed();
        assertThat(referenceSummary).hasTitleText("Static Analysis: 4 warnings");
        assertThat(referenceSummary).hasAggregation("FindBugs, CPD, CheckStyle, PMD");
        assertThat(referenceSummary).hasNewSize(0);
        assertThat(referenceSummary).hasFixedSize(0);
        assertThat(referenceSummary).hasReferenceBuild(0);

        reconfigureJobWithResource(job, "build_status_test/build_02");

        Build build = buildJob(job);

        build.open();

        AnalysisSummary analysisSummary = new AnalysisSummary(build, ANALYSIS_ID);
        assertThat(analysisSummary).isDisplayed();
        assertThat(analysisSummary).hasTitleText("Static Analysis: 25 warnings");
        assertThat(analysisSummary).hasAggregation("FindBugs, CPD, CheckStyle, PMD");
        assertThat(analysisSummary).hasNewSize(23);
        assertThat(analysisSummary).hasFixedSize(2);
        assertThat(analysisSummary).hasReferenceBuild(1);

        AnalysisResult result = analysisSummary.openOverallResult();
        assertThat(result).hasActiveTab(Tab.TOOLS);
        assertThat(result).hasTotal(25);
        assertThat(result).hasOnlyAvailableTabs(
                Tab.TOOLS, Tab.PACKAGES, Tab.FILES, Tab.CATEGORIES, Tab.TYPES, Tab.ISSUES);
    }

    /**
     * Test to check that the issue filter can be configured and is applied.
     */
    @Test
    public void should_filter_issues_by_include_and_exclude_filters() {
        FreeStyleJob job = createFreeStyleJob("issue_filter/checkstyle-result.xml");
        job.addPublisher(IssuesRecorder.class, recorder -> {
            recorder.setTool("CheckStyle");
            recorder.setEnabledForFailure(true);
            recorder.addIssueFilter("Exclude categories", "Checks");
            recorder.addIssueFilter("Include types", "JavadocMethodCheck");
        });

        job.save();

        Build build = buildJob(job);

        assertThat(build.getConsole()).contains(
                "Applying 2 filters on the set of 4 issues (3 issues have been removed, 1 issues will be published)");

        AnalysisResult result = openAnalysisResult(build, "checkstyle");

        IssuesTable issuesTable = result.openIssuesTable();
        assertThat(issuesTable).hasSize(1);
    }

    private void reconfigureJobWithResource(final FreeStyleJob job, final String fileName) {
        job.configure(() -> job.copyResource(WARNINGS_PLUGIN_PREFIX + fileName));
    }

    private IssuesRecorder addRecorder(final FreeStyleJob job) {
        return job.addPublisher(IssuesRecorder.class, recorder -> {
            recorder.setTool("CheckStyle");
            recorder.addTool("FindBugs");
            recorder.addTool("PMD");
            recorder.addTool("CPD",
                    cpd -> cpd.setHighThreshold(8).setNormalThreshold(3));
            recorder.setEnabledForFailure(true);
        });
    }

    /**
     * Runs a pipeline that publishes checkstyle warnings. Verifies the content of the info and error log view.
     */
    @Test
    @WithPlugins({"workflow-cps", "pipeline-stage-step", "workflow-durable-task-step", "workflow-basic-steps"})
    public void should_show_info_and_error_messages_in_pipeline() {
        WorkflowJob job = jenkins.jobs.create(WorkflowJob.class);
        String resource = job.copyResourceStep(WARNINGS_PLUGIN_PREFIX + CHECKSTYLE_XML);
        job.script.set("node {\n"
                + resource.replace("\\", "\\\\")
                + "recordIssues enabledForFailure: true, tool: checkStyle(), sourceCodeEncoding: 'UTF-8'"
                + "}");

        job.sandbox.check();
        job.save();

        Build pipeline = buildJob(job);
        verifyInfoAndErrorMessages(pipeline);
    }

    private void verifyInfoAndErrorMessages(final Build build) {
        InfoView infoView = new InfoView(build, CHECKSTYLE_ID);
        infoView.open();

        assertThat(infoView.getErrorMessages()).hasSize(1 + 1 + 1 + 11);
        assertThat(infoView).hasErrorMessages("Can't resolve absolute paths for some files:",
                "Can't create fingerprints for some files:");

        assertThat(infoView).hasInfoMessages(
                "-> found 1 file",
                "-> found 11 issues (skipped 0 duplicates)",
                "Post processing issues on 'Master' with source code encoding 'UTF-8'",
                "-> 0 resolved, 1 unresolved, 0 already resolved",
                "-> 0 copied, 0 not in workspace, 1 not-found, 0 with I/O error",
                "-> resolved module names for 11 issues",
                "-> resolved package names of 1 affected files",
                "-> created fingerprints for 0 issues",
                "No valid reference build found that meets the criteria (NO_JOB_FAILURE - SUCCESSFUL_QUALITY_GATE)",
                "All reported issues will be considered outstanding",
                "No quality gates have been set - skipping",
                "Health report is disabled - skipping");
    }

    /**
     * Creates and builds a maven job and verifies that all warnings are shown in the summary and details views.
     */
    @Test
    @WithPlugins("maven-plugin")
    public void should_show_maven_warnings_in_maven_project() {
        MavenModuleSet job = createMavenProject();
        copyResourceFilesToWorkspace(job, SOURCE_VIEW_FOLDER + "pom.xml");
        configureJob(job, "Maven", "");
        job.save();

        Build build = buildFailingJob(job);
        build.open();

        AnalysisSummary summary = new AnalysisSummary(build, MAVEN_ID);
        assertThat(summary).isDisplayed();
        assertThat(summary).hasTitleText("Maven: 2 warnings");
        assertThat(summary).hasNewSize(0);
        assertThat(summary).hasFixedSize(0);
        assertThat(summary).hasReferenceBuild(0);

        AnalysisResult mavenDetails = summary.openOverallResult();
        assertThat(mavenDetails).hasActiveTab(Tab.TYPES);
        assertThat(mavenDetails).hasTotal(2);
        assertThat(mavenDetails).hasOnlyAvailableTabs(Tab.TYPES, Tab.ISSUES);

        IssuesTable issuesTable = mavenDetails.openIssuesTable();

        DefaultWarningsTableRow firstRow = issuesTable.getRowAs(0, DefaultWarningsTableRow.class);
        ConsoleLogView sourceView = firstRow.openConsoleLog();
        assertThat(sourceView).hasTitle("Console Details");
        assertThat(sourceView).hasHighlightedText("[WARNING]\n"
                + "[WARNING] Some problems were encountered while building the effective model for edu.hm.hafner.irrelevant.groupId:random-artifactId:jar:1.0\n"
                + "[WARNING] 'build.plugins.plugin.version' for org.apache.maven.plugins:maven-compiler-plugin is missing. @ line 13, column 15\n"
                + "[WARNING]\n"
                + "[WARNING] It is highly recommended to fix these problems because they threaten the stability of your build.\n"
                + "[WARNING]\n"
                + "[WARNING] For this reason, future Maven versions might no longer support building such malformed projects.\n"
                + "[WARNING]");
    }

    /**
     * Verifies that package and namespace names are resolved.
     */
    @Test
    @WithPlugins("maven-plugin")
    public void should_resolve_packages_and_namespaces() {
        MavenModuleSet job = createMavenProject();
        job.copyDir(job.resource(SOURCE_VIEW_FOLDER));
        configureJob(job, "Eclipse ECJ", "**/*Classes.txt");
        job.save();

        Build build = buildFailingJob(job);
        build.open();

        AnalysisSummary analysisSummary = new AnalysisSummary(build, "eclipse");
        AnalysisResult result = analysisSummary.openOverallResult();
        assertThat(result).hasActiveTab(Tab.MODULES);
        assertThat(result).hasTotal(9);
        assertThat(result).hasOnlyAvailableTabs(Tab.MODULES, Tab.PACKAGES, Tab.FILES, Tab.ISSUES);

        LinkedHashMap<String, String> filesToPackages = new LinkedHashMap<>();
        filesToPackages.put("NOT_EXISTING_FILE", NO_PACKAGE);
        filesToPackages.put("SampleClassWithBrokenPackageNaming.java", NO_PACKAGE);
        filesToPackages.put("SampleClassWithNamespace.cs", "SampleClassWithNamespace");
        filesToPackages.put("SampleClassWithNamespaceBetweenCode.cs", "NestedNamespace");
        filesToPackages.put("SampleClassWithNestedAndNormalNamespace.cs", "SampleClassWithNestedAndNormalNamespace");
        filesToPackages.put("SampleClassWithoutNamespace.cs", NO_PACKAGE);
        filesToPackages.put("SampleClassWithoutPackage.java", NO_PACKAGE);
        filesToPackages.put("SampleClassWithPackage.java", "edu.hm.hafner.analysis._123.int.naming.structure");
        filesToPackages.put("SampleClassWithUnconventionalPackageNaming.java", NO_PACKAGE);

        int row = 0;
        for (Entry<String, String> fileToPackage : filesToPackages.entrySet()) {
            IssuesTable issuesTable = result.openIssuesTable();
            DefaultWarningsTableRow tableRow = issuesTable.getRowAs(row,
                    DefaultWarningsTableRow.class); // TODO: create custom assertions

            String actualFileName = fileToPackage.getKey();
            assertThat(tableRow).as("Row %d", row).hasFileName(actualFileName);
            assertThat(tableRow).as("Row %d", row).hasPackageName(fileToPackage.getValue());

            if (row != 0) { // first row has no file attached
                SourceView sourceView = tableRow.openSourceCode();
                assertThat(sourceView).hasFileName(actualFileName);
                String expectedSourceCode = toString(SOURCE_VIEW_FOLDER + actualFileName);
                assertThat(sourceView.getSourceCode()).isEqualToIgnoringWhitespace(expectedSourceCode);
            }
            row++;
        }
    }

    /**
     * Verifies that warnings can be parsed on a agent as well.
     */
    @Test
    @WithDocker
    @WithPlugins("ssh-slaves")
    @WithCredentials(credentialType = WithCredentials.SSH_USERNAME_PRIVATE_KEY, values = {CREDENTIALS_ID, CREDENTIALS_KEY})
    public void should_parse_warnings_on_agent() {
        DumbSlave dockerAgent = createDockerAgent();
        FreeStyleJob job = createFreeStyleJobForDockerAgent(dockerAgent, "issue_filter/checkstyle-result.xml");
        job.addPublisher(IssuesRecorder.class, recorder -> {
            recorder.setTool("CheckStyle", "**/checkstyle-result.xml");
        });
        job.save();

        Build build = buildJob(job);
        build.open();

        AnalysisSummary summary = new AnalysisSummary(build, "checkstyle");
        assertThat(summary).isDisplayed();
        assertThat(summary).hasTitleText("CheckStyle: 4 warnings");
        assertThat(summary).hasNewSize(0);
        assertThat(summary).hasFixedSize(0);
        assertThat(summary).hasReferenceBuild(0);
    }

    private FreeStyleJob createFreeStyleJobForDockerAgent(final Slave dockerAgent, final String... resourcesToCopy) {
        FreeStyleJob job = createFreeStyleJob(resourcesToCopy);
        job.configure();
        job.setLabelExpression(dockerAgent.getName());
        return job;
    }

    /**
     * Returns a docker container that can be used to host git repositories and which can be used as build agent. If the
     * container is used as agent and git server, then you need to use the file protocol to access the git repository
     * within Jenkins.
     *
     * @return the container
     */
    private JavaGitContainer getDockerContainer() {
        return dockerContainer.get();
    }

    /**
     * Creates an agent in a Docker container.
     *
     * @return the new agent ready for new builds
     */
    private DumbSlave createDockerAgent() {
        DumbSlave agent = jenkins.slaves.create(DumbSlave.class);

        agent.setExecutors(1);
        agent.remoteFS.set("/tmp/");
        SshSlaveLauncher launcher = agent.setLauncher(SshSlaveLauncher.class);

        JavaGitContainer container = getDockerContainer();
        launcher.host.set(container.ipBound(22));
        launcher.port(container.port(22));
        launcher.setSshHostKeyVerificationStrategy(SshSlaveLauncher.NonVerifyingKeyVerificationStrategy.class);
        launcher.selectCredentials(CREDENTIALS_ID);

        agent.save();

        agent.waitUntilOnline();

        assertThat(agent.isOnline()).isTrue();

        return agent;
    }

    /**
     * Creates and builds a FreestyleJob for a specific static analysis tool.
     *
     * @param toolName
     *         the name of the tool
     * @param configuration
     *         the configuration steps for the static analysis tool
     * @param resourcesToCopy
     *         the resources which shall be copied to the workspace
     *
     * @return the finished build
     */
    private Build createAndBuildFreeStyleJob(final String toolName, final Consumer<StaticAnalysisTool> configuration,
            final String... resourcesToCopy) {
        FreeStyleJob job = createFreeStyleJob(toolName, configuration, resourcesToCopy);

        return buildJob(job);
    }

    /**
     * Creates a FreestyleJob for a specific static analysis tool.
     *
     * @param toolName
     *         the name of the tool
     * @param configuration
     *         the configuration steps for the static analysis tool
     * @param resourcesToCopy
     *         the resources which shall be copied to the workspace
     *
     * @return the created job
     */
    private FreeStyleJob createFreeStyleJob(final String toolName, final Consumer<StaticAnalysisTool> configuration,
            final String... resourcesToCopy) {
        FreeStyleJob job = createFreeStyleJob(resourcesToCopy);
        IssuesRecorder recorder = job.addPublisher(IssuesRecorder.class);
        recorder.setTool(toolName, configuration);
        job.save();
        return job;
    }

    /**
     * Opens the AnalysisResult and returns the corresponding PageObject representing it.
     *
     * @param build
     *         the build
     * @param id
     *         the id of the static analysis tool
     *
     * @return the PageObject representing the AnalysisResult
     */
    private AnalysisResult openAnalysisResult(final Build build, final String id) {
        AnalysisResult resultPage = new AnalysisResult(build, id);
        resultPage.open();
        return resultPage;
    }

    private FreeStyleJob createFreeStyleJob(final String... resourcesToCopy) {
        FreeStyleJob job = jenkins.getJobs().create(FreeStyleJob.class);
        ScrollerUtil.hideScrollerTabBar(driver);
        for (String resource : resourcesToCopy) {
            job.copyResource(WARNINGS_PLUGIN_PREFIX + resource);
        }
        return job;
    }

    private MavenModuleSet createMavenProject() {
        MavenInstallation.installSomeMaven(jenkins);
        return jenkins.getJobs().create(MavenModuleSet.class);
    }

    private void configureJob(final MavenModuleSet job, final String toolName, final String pattern) {
        IssuesRecorder recorder = job.addPublisher(IssuesRecorder.class);
        recorder.setToolWithPattern(toolName, pattern);
        recorder.setEnabledForFailure(true);
    }

    private Build buildFailingJob(final Job job) {
        return buildJob(job).shouldFail();
    }

    private Build buildJob(final Job job) {
        return job.startBuild().waitUntilFinished();
    }

    private void copyResourceFilesToWorkspace(final Job job, final String... resources) {
        for (String file : resources) {
            job.copyResource(file);
        }
    }

    /**
     * Finds a resource with the given name and returns the content (decoded with UTF-8) as String.
     *
     * @param fileName
     *         name of the desired resource
     *
     * @return the content represented as {@link String}
     */
    private String toString(final String fileName) {
        return new String(readAllBytes(fileName), StandardCharsets.UTF_8);
    }

    /**
     * Reads the contents of the desired resource. The rules for searching resources associated with this test class are
     * implemented by the defining {@linkplain ClassLoader class loader} of this test class.  This method delegates to
     * this object's class loader.  If this object was loaded by the bootstrap class loader, the method delegates to
     * {@link ClassLoader#getSystemResource}.
     * <p>
     * Before delegation, an absolute resource name is constructed from the given resource name using this algorithm:
     * <p>
     * <ul>
     * <li> If the {@code name} begins with a {@code '/'} (<tt>'&#92;u002f'</tt>), then the absolute name of the
     * resource is the portion of the {@code name} following the {@code '/'}.</li>
     * <li> Otherwise, the absolute name is of the following form:
     * <blockquote> {@code modified_package_name/name} </blockquote>
     * <p> Where the {@code modified_package_name} is the package name of this object with {@code '/'}
     * substituted for {@code '.'} (<tt>'&#92;u002e'</tt>).</li>
     * </ul>
     *
     * @param fileName
     *         name of the desired resource
     *
     * @return the content represented by a byte array
     */
    private byte[] readAllBytes(final String fileName) {
        try {
            return Files.readAllBytes(getPath(fileName));
        }
        catch (IOException | URISyntaxException e) {
            throw new AssertionError("Can't read resource " + fileName, e);
        }
    }

    private Path getPath(final String name) throws URISyntaxException {
        URL resource = getClass().getResource(name);
        if (resource == null) {
            throw new AssertionError("Can't find resource " + name);
        }
        return Paths.get(resource.toURI());
    }

}

