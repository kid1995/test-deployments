import de.signaliduna.BitbucketPr
import de.signaliduna.BitbucketRepo

/**
 * Executes a sonar pull request analysis and reports results to bitbucket
 */
public void sonarPr(BitbucketRepo repo, String versionScript, BitbucketPr pr, boolean failBuildOnQualityGate, String xmlReportPaths = "build/jacoco/test/jacocoTestReport.xml", String additionalConfiguration = "") {
    def projectName = "${repo.projectName}_${repo.repoName}"
    sonarPullRequest(repo, projectName, versionScript, pr, failBuildOnQualityGate, xmlReportPaths, additionalConfiguration)
}

/**
 * Executes a sonar pull request analysis and reports results to bitbucket.
 * The monorepo project name is a project contained within a single repository.
 */
public void sonarPrMulti(BitbucketRepo repo, String projectName, String versionScript, BitbucketPr pr, boolean failBuildOnQualityGate, String xmlReportPaths = "build/jacoco/test/jacocoTestReport.xml", String additionalConfiguration = "") {
    def sonarProjectName = "${repo.projectName}_${repo.repoName}_${projectName}"
    sonarPullRequest(repo, sonarProjectName, versionScript, pr, failBuildOnQualityGate, xmlReportPaths, additionalConfiguration)
}

/**
 * Executes a sonar pull request analysis and reports results to bitbucket
 */
private void sonarPullRequest(BitbucketRepo repo, String sonarProjectName, String versionScript, BitbucketPr pr, boolean failBuildOnQualityGate, String xmlReportPaths, String additionalConfiguration ) {
    withSonarQubeEnv("SDA-SonarQube") { // Will pick the global server connection you have configured
        sh """
            . ${versionScript}
            ./gradlew sonar \
                -Dsonar.qualitygate.wait=${failBuildOnQualityGate} \
                -Dsonar.scm.provider=git \
                -Dsonar.projectKey=${sonarProjectName} \
                -Dsonar.projectName=${sonarProjectName} \
                -Dsonar.pullrequest.key=${pr.id} \
                -Dsonar.pullrequest.base=${pr.target} \
                -Dsonar.pullrequest.branch=${env.BRANCH_NAME} \
                -Dsonar.pullrequest.provider=bitbucketserver \
                -Dsonar.pullrequest.bitbucketserver.project=${repo.projectName} \
                -Dsonar.pullrequest.bitbucketserver.repository=${repo.repoName} \
                ${xmlReportPaths ? "-Dsonar.coverage.jacoco.xmlReportPaths=" + xmlReportPaths : ""} \
                -Dorg.gradle.jvmargs="-Xmx2g -XX:MaxMetaspaceSize=512m -XX:+HeapDumpOnOutOfMemoryError" \
                ${additionalConfiguration} \
                --info \
                --stacktrace
        """
    }
}

/**
 * Executes a sonar analysis on the current branch
 */
public void sonarCurrentBranch(BitbucketRepo repo, String versionScript, boolean failBuildOnQualityGate, String xmlReportPaths = "build/jacoco/test/jacocoTestReport.xml", String additionalConfiguration = "") {
    def projectName = "${repo.projectName}_${repo.repoName}"
    sonarBranch(projectName, versionScript, failBuildOnQualityGate, xmlReportPaths, additionalConfiguration)
}

/**
 * Executes a sonar analysis on the current branch with a project name,
 * suitable for mono repository setups which contain multiple java projects.
 */
public void sonarCurrentBranchMulti(BitbucketRepo repo, String projectName, String versionScript, boolean failBuildOnQualityGate, String xmlReportPaths = "build/jacoco/test/jacocoTestReport.xml", String additionalConfiguration = "") {
    def sonarProjectName = "${repo.projectName}_${repo.repoName}_${projectName}"
    sonarBranch(sonarProjectName, versionScript, failBuildOnQualityGate, xmlReportPaths, additionalConfiguration)
}

/**
 * Executes a sonar analysis on the current branch with a project name that is used as key and name in sonar.
 */
private void sonarBranch(String sonarProjectName, String versionScript, boolean failBuildOnQualityGate, String xmlReportPaths, String additionalConfiguration ) {
    withSonarQubeEnv("SDA-SonarQube") { // Will pick the global server connection you have configured
        sh """
            . ${versionScript}
            ./gradlew sonar \
                -Dsonar.qualitygate.wait=${failBuildOnQualityGate} \
                -Dsonar.scm.provider=git \
                -Dsonar.projectKey=${sonarProjectName} \
                -Dsonar.projectName=${sonarProjectName} \
                -Dsonar.branch.name=${env.BRANCH_NAME} \
                ${xmlReportPaths ? "-Dsonar.coverage.jacoco.xmlReportPaths=" + xmlReportPaths : ""} \
                -Dorg.gradle.jvmargs="-Xmx2g -XX:MaxMetaspaceSize=512m -XX:+HeapDumpOnOutOfMemoryError" \
                ${additionalConfiguration} \
                --info \
                --stacktrace
        """
    }
}

public String sonarNameFromFolder(String folder) {
    return folder.toLowerCase()
            .replaceAll("/", "")  // shorten 'sdasi-blueprint/' -> 'sdasi-blueprint'
}