@groovy.transform.Field
def versionScript = "use-jdk-8"


void version(String version) {
  switch(version) {
    case "8":
    case "11":
    case "17":
    case "21":
        versionScript = "use-jdk-${version}"
    default:
        throw new IllegalStateException("Unsupported java version " + version)
  }
}

/**
 * Build java project excluding check.
 * Assembles the distribution content and installs it on the current machine by using the gradle distribution plugin.
 *
 * @param folder - the folder where the java project relies in.
 */
public void build(String folder = "") {
    dir(folder) {
        sh """
            . ${versionScript}
            ./gradlew clean installDist -x check
        """
    }
}

void test(String folder) {
    dir(folder) {
        try {
            sh """
                . ${versionScript}
                ./gradlew test
            """
        } finally {
            step([$class: 'JUnitResultArchiver', testResults: '**/build/test-results/test/*.xml'])
        }
    }
}

void checkMulti(Set<String> foldersSet, int owaspFailedHigh = 3, int owaspFailedNormal = 99, coverageExclusionPattern = '**/test/**, **/integTest/**, kotlin/main/*, **/gradle/kotlin/dsl/**, **/org/gradle/accessors/dm/**', int owaspFailedCritical = 3, int owaspFailedLow = 99) {
    String[] folders = foldersSet as String[]
    checkMulti(folders, owaspFailedHigh, owaspFailedNormal, coverageExclusionPattern, owaspFailedCritical, owaspFailedLow)
}
/**
 * Do the gradle check and perform verification tasks, such as running tests.
 * Publishes results to the given folders.
 *
 * @param folders - The folders where to publish the results.
 * @param owaspFailedHigh - The threshold for owasp high failures.
 * @param owaspFailedNormal - The threshold for owasp normal/medium failures.
 * @param coverageExclusionPattern - Pattern for resources which should be excluded from code coverage check.
 * @param owaspFailedCritical - The threshold for owasp critical failures.
 * @param owaspFailedLow - The threshold for owasp low failures.
 */
void checkMulti(String[] folders, int owaspFailedHigh = 3, int owaspFailedNormal = 99, coverageExclusionPattern = '**/test/**, **/integTest/**, kotlin/main/*, **/gradle/kotlin/dsl/**, **/org/gradle/accessors/dm/**', int owaspFailedCritical = 3, int owaspFailedLow = 99) {
    try {
        sh """
            export DOCKER_HOST=tcp://${si_docker.getDockerHost()}
            export DOCKER_TLS_VERIFY=1
            export DOCKER_CERT_PATH=/tls/testcontainers
            . ${versionScript}
            ./gradlew check
        """
    } finally {
        for (String folder : folders) {
            step([$class: 'JUnitResultArchiver', testResults: '**/' + folder + '/build/test-results/**/*.xml'])
        }
    }
    sh """
        . ${versionScript}
        ./gradlew dependencyCheckAnalyze
    """
    for (String folder : folders) {
        publishCoverage(folder, coverageExclusionPattern)
        publishDependencyCheck(folder, owaspFailedCritical, owaspFailedHigh, owaspFailedNormal, owaspFailedLow)
    }
}

/**
 * Runs gradle check which performs verification tasks, such as running tests.
 * Additionally, it performs a dependency check for which you can define optional custom thresholds.
 * At the end, it publishes the verification results for a given folder.
 *
 * This function can be called for gradle multi-project repos.
 *  - gradleCheck runs `gradle check` from the root context which will check also all subprojects.
 *    -> publishCoverage will collect all *.exec files from all subprojects at once because of the wildcard
 *  - dependencyCheckAggregate will analyze the root and subprojects together
 *    -> the dependency check task must be configured in a root build.gradle file
 *
 * It's a different approach compared to the checkMulti-method which calls checks every project separately
 * and makes the statistic in jenkins more usable because we only get one code coverage and dependency check
 * entry combining all projects instead of one entry for every project
 *
 * @param folder - The folder where to publish the results.
 * @param owaspFailedHigh - The threshold for owasp high failures.
 * @param owaspFailedNormal - The threshold for owasp normal/medium failures.
 * @param coverageExclusionPattern - Pattern for resources which should be excluded from code coverage check.
 * @param owaspFailedCritical - The threshold for owasp critical failures.
 * @param owaspFailedLow - The threshold for owasp low failures.
 */
void checkMulti(String folder = "", int owaspFailedHigh = 3, int owaspFailedNormal = 99, coverageExclusionPattern = '**/test/**, **/integTest/**, kotlin/main/*, **/gradle/kotlin/dsl/**, **/org/gradle/accessors/dm/**', int owaspFailedCritical = 3, int owaspFailedLow = 99) {
    gradleCheck(folder, coverageExclusionPattern)
    dependencyCheckAggregate(folder, owaspFailedHigh, owaspFailedNormal, owaspFailedCritical, owaspFailedLow)
}

/**
 * Runs gradle check which performs verification tasks, such as running tests.
 * Additionally, it performs a dependency check for which you can define optional custom thresholds.
 * At the end, it publishes the verification results for a given folder.
 *
 * @param folder - The folder where to publish the results.
 * @param owaspFailedHigh - The threshold for owasp high failures.
 * @param owaspFailedNormal - The threshold for owasp normal/medium failures.
 * @param coverageExclusionPattern - Pattern for resources which should be excluded from code coverage check.
 * @param owaspFailedCritical - The threshold for owasp critical failures.
 * @param owaspFailedLow - The threshold for owasp low failures.
 */
void check(String folder = "", int owaspFailedHigh = 3, int owaspFailedNormal = 99, coverageExclusionPattern = '**/test/**, **/integTest/**', int owaspFailedCritical = 3, int owaspFailedLow = 99) {
    gradleCheck(folder, coverageExclusionPattern)
    dependencyCheckAnalyze(folder, owaspFailedHigh, owaspFailedNormal, owaspFailedCritical, owaspFailedLow)
}

/**
 * Runs gradle check which performs verification tasks, such as running tests.
 * At the end, it publishes the verification results for a given folder.
 *
 * @param folder - The folder where to publish the results.
 * @param coverageExclusionPattern - Pattern for resources which should be excluded from code coverage check.
 */
void gradleCheck(String folder, coverageExclusionPattern = '**/test/**, **/integTest/**') {
	dir(folder) {
        try {
            sh """
                export DOCKER_HOST=tcp://${si_docker.getDockerHost()}
                export DOCKER_TLS_VERIFY=1
                export DOCKER_CERT_PATH=/tls/testcontainers
                . ${versionScript}
                ./gradlew check
            """
        } finally {
            step([$class: 'JUnitResultArchiver', testResults: '**/build/test-results/**/*.xml'])
        }
    }
    publishCoverage(folder, coverageExclusionPattern)
}

void createBddReport(String folder, String reportDir = 'target/site/serenity') {
    dir(folder) {
        sh """
            . ${versionScript}
            ./gradlew aggregate
        """
    }
    publishHTML(target: [
        reportName : 'Serenity',
        reportDir:   "$folder/$reportDir",
        reportFiles: 'index.html',
        keepAll:     true,
        alwaysLinkToLastBuild: true,
        allowMissing: false
    ])
}

/**
 * Runs a sonar scan via gradle, publishes results to the jenkins server.
 * IMPORTANT: Assumes that tests have been run previously for coverage
 */
void staticAnalysis(String folder = "", boolean failBuildOnQualityGate = false, String xmlReportPaths = "build/jacoco/test/jacocoTestReport.xml", String additionalConfiguration = "") {
    dir(folder) {
        def repo = si_git.parseGitUrl()
        def pr = si_git.getPullRequest(repo)
        if (pr != null) {
            si_sonar_java.sonarPr(repo, versionScript, pr, failBuildOnQualityGate, xmlReportPaths, additionalConfiguration)
        } else {
            si_sonar_java.sonarCurrentBranch(repo, versionScript, failBuildOnQualityGate, xmlReportPaths, additionalConfiguration)
        }
    }
}

/**
 * Runs a sonar scan via gradle, publishes results to the jenkins server.
 * This method is to be used only with a mono repository setup,
 * where a repository contains multiple projects.
 * IMPORTANT: Assumes that tests have been run previously for coverage
 *
 * @param folder - folder of a subproject
 * @param failBuildOnQualityGate
 * @param xmlReportPaths - can be overridden to configure custom paths e.g. to include shared libraries in analysis.
 */
void staticAnalysisMulti(String folder, boolean failBuildOnQualityGate = false, String xmlReportPaths = "build/jacoco/test/jacocoTestReport.xml", String additionalConfiguration = "") {
    dir(folder) {
        def repo = si_git.parseGitUrl()
        def projectName = si_sonar_java.sonarNameFromFolder(folder)
        def pr = si_git.getPullRequest(repo)
        if (pr != null) {
            si_sonar_java.sonarPrMulti(repo, projectName, versionScript, pr, failBuildOnQualityGate, xmlReportPaths, additionalConfiguration)
        } else {
            si_sonar_java.sonarCurrentBranchMulti(repo, projectName, versionScript, failBuildOnQualityGate, xmlReportPaths, additionalConfiguration)
        }
    }
}

/**
 * Runs a sonar scan via gradle, publishes results to the sonar server.
 * This method is to be used only with a mono repository setup,
 * where a repository contains multiple projects.
 * IMPORTANT: Assumes that tests have been run previously for coverage
 *
 * The xml report path must be configured by sonar-properties in build.gradle
 *
 * @param folder - folder of a subproject
 * @param failBuildOnQualityGate
 * @param xmlReportPaths - can be overridden to configure custom paths e.g. to include shared libraries in analysis.
 */
void staticAnalysisMultiProject(String folder = "", boolean failBuildOnQualityGate = false, String additionalConfiguration = "") {
    dir(folder) {
        def repo = si_git.parseGitUrl()
        def pr = si_git.getPullRequest(repo)
        if (pr != null) {
            si_sonar_java.sonarPr(repo, versionScript, pr, failBuildOnQualityGate, "", additionalConfiguration)
        } else {
            si_sonar_java.sonarCurrentBranch(repo, versionScript, failBuildOnQualityGate, "", additionalConfiguration)
        }
    }
}

/**
 * Execute any task defined in the projects build.gradle
 * @param folder - The folder where the gradle task will be executed.
 * @param task - The gradle task to be executed
 */
void executeGradleTask(String folder, String task) {
    dir(folder) {
        sh """
            . ${versionScript}
            ./gradlew ${task}
        """
    }
}

void dependencyCheckAnalyze(String folder, int owaspFailedHigh = 3, int owaspFailedNormal = 99, int owaspFailedCritical = 3, int owaspFailedLow = 99) {
    dir(folder) {
        sh """
            . ${versionScript}
            ./gradlew dependencyCheckAnalyze
        """
    }
    publishDependencyCheck(folder, owaspFailedCritical, owaspFailedHigh, owaspFailedNormal, owaspFailedLow)
}

void dependencyCheckAggregate(String folder, int owaspFailedHigh = 3, int owaspFailedNormal = 99, int owaspFailedCritical = 3, int owaspFailedLow = 99) {
    dir(folder) {
        sh """
            . ${versionScript}
            ./gradlew dependencyCheckAggregate
        """
    }
    publishDependencyCheck(folder, owaspFailedCritical, owaspFailedHigh, owaspFailedNormal, owaspFailedLow)
}

private void publishDependencyCheck(String folder, int owaspFailedCritical, int owaspFailedHigh, int owaspFailedNormal, int owaspFailedLow) {
    // If an exception occurred above, we assume that the job is running on sda-jenkins.system.local - new nomenclature of findings
    try {
        step(
            [
                $class: 'DependencyCheckPublisher',
                pattern: getDependencyCheckPattern(folder),
                failedTotalCritical: "$owaspFailedCritical",
                failedTotalHigh: "$owaspFailedHigh",
                failedTotalMedium: "$owaspFailedNormal",
                failedTotalLow: "$owaspFailedLow"
            ]
        )
        // When DependencyCheckPublisher throws an error, Jenkins doesn't fail the build stage and only fails the build at the very end.
        // We should manually set the build result to UNSTABLE for this stage. See: https://stackoverflow.com/a/66013797
        if (currentBuild.result == 'FAILURE') {
            unstable('Check if the dependency check findings exceeded the configured thresholds.')
        }
    } catch (Exception e2) {
        println "Publish Dependency Check reports failed. Exception was ${e2.getMessage()}"
    }
}

/**
 * Calculate coverage from test report files and publish results to Jenkins.
 */
private void publishCoverage(String folder, coverageExclusionPattern) {
    step([$class: 'JacocoPublisher',
        execPattern: "${folder ? folder + "/" : ""}**/build/jacoco/*.exec",
        classPattern: "${folder ? folder + "/" : ""}**/build/classes",
        sourcePattern: "${folder ? folder + "/" : ""}**/src/main/java",
        exclusionPattern: "${coverageExclusionPattern}"])
}

private String getDependencyCheckPattern(String folder) {
    final String generalPattern = "**/reports/dependencycheck/dependency-check-report.xml"

    if (folder) {
        if (folder == "./") {
            return generalPattern
        }
        if (folder.endsWith("/")) {
            return "${folder}${generalPattern}"
        }
        return "${folder}/${generalPattern}"
    }
    return generalPattern
}
