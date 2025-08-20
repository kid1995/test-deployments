import de.signaliduna.BitbucketPr
import de.signaliduna.BitbucketRepo

@groovy.transform.Field
def currentNodeVersion = '/srv/dev/node-v18-linux-x64/bin'

/**
 * This registry is used for publishing artifacts, *not* as a source for installing project dependencies.
 */
@groovy.transform.Field
def sdasiRegistryUri = 'npmrepo.system.local/repository/si-sda/'

@groovy.transform.Field
def sdasiRegistry = 'https://' + sdasiRegistryUri

/**
 * This registry is used to scan a project for vulnerabilities via npm audit. It is *not* a source for installing project dependencies.
 */
@groovy.transform.Field
def npmRegistry = 'https://registry.npmjs.org'

public void node_version(String version) {
    switch(version) {
        case '14':
        case '14.17':
        case '14.17.0':
            printDeprecationWarning('si_npm.node_version(' + version + ')', 'si_npm.node_version(18)')    
        case '16':
        case '16.13':
        case '16.13.2':
      	case '16.14':
      	case '16.14.2':
      	case '16.17':
      	case '16.17.0':
        case '17':
        case '17.9':
        case '18':
        case '18.12':
        case '18.12.1':
        case '18.16':
        case '18.16.0':
        case '19':
        case '19.0':
        case '19.0.0':
        case '20':
        case '20.0.0':
        case '20.13':
        case '20.13.1':
        case '21.4.0':
        case '22':
        case '22.4':
        case '22.4.1':
        case '22.14':
        case '22.14.0':
        case '22.17':
        case '22.17.0':
            currentNodeVersion = "/srv/dev/node-v${version}-linux-x64/bin"
        default:
            throw new IllegalStateException("Unsupported node version ${version}")
    }
}

/**
 * Install dependencies
 *
 * @param path The path were the command will be executed.
 */
public void ciInstall(String path) {
    npmExecCmd(path, 'ci')
}

/**
 * Scan for vulnerabilities in dependencies
 *
 * ci:audit is used by default to scan for vulnerabilities. If no ci:audit script exists, npm audit is used as fallback.
 *
 * @param path The path were the command will be executed.
 * @param auditLevel The minimum vulnerability level that will cause the command to fail (low|moderate|high|critical).
 */
public void ciAudit(String path, String auditLevel = "high") {
    dir(path) {
        ciAuditScriptExists = sh(
            script: """
                export PATH=${currentNodeVersion}:\\${PATH}
                npm run | grep '${toCiScript('audit', null)}'
            """,
            returnStatus: true
        ) == 0
    }

    args = "--registry=${npmRegistry} --audit-level=${auditLevel}"

    if (ciAuditScriptExists) {
        npmRun(path, toCiScript('audit', null), args)
    } else {
        npmExecCmd(path, 'audit', args)
    }
}

/**
 * Run linter (code analysis), publish results
 *
 * @param path The path were the command will be executed.
 * @param resultsPath The path where the Checkstyle results can be found. Defaults to "${path}reports/checkstyle-*.xml".
 */
public void ciLint(String path, String resultsPath = null) {
    ciLintProject(null, path, resultsPath)
}

/**
 * Run linter (code analysis), publish results
 *
 * @param projectName Used as suffix for ci:lint (e.g. ci:lint:foo)
 * @param path The path were the command will be executed.
 * @param resultsPath The path where the Checkstyle results can be found. Defaults to "${path}reports/checkstyle-*.xml".
 */
public void ciLintProject(String projectName, String path, String resultsPath = null) {
    if (resultsPath == null) {
        resultsPath = "${path}reports/checkstyle-*.xml"
        
        dir(path) {
            sh 'mkdir -p reports'
        }
    }


    npmRun(path, toCiScript('lint', projectName))

    try {
        step([
            $class: 'CheckStylePublisher',
            pattern: resultsPath,
            unstableTotalAll: '0'
        ])
    } catch (Exception e) {
        echo "Warning: no reports for lint results found at path ${resultsPath}"
    }
}

/**
 * Create deployment artefact
 *
 * @param path The path were the command will be executed.
 */
public void ciBuild(String path) {
    ciBuildProject(null, path)
}

/**
 * Create deployment artefact
 *
 * @param projectName Used as suffix for ci:build (e.g. ci:build:foo)
 * @param path The path were the command will be executed.
 */
public void ciBuildProject(String projectName, String path) {
    npmRun(path, toCiScript('build', projectName))
}

/**
 * Run unit tests, publish results and coverage
 *
 * @param path The path were the command will be executed.
 * @param resultsPath The path where the JUnit test results can be found. Defaults to "**\/${path}reports/junit-*.xml".
 * @param coveragePath The path where the generated Coberturacoverage can be found. Defaults to "${path}coverage/**\/cobertura-coverage.xml".
 */
public void ciTest(String path, String resultsPath = null, String coveragePath = null) {
    ciTestProject(null, path, resultsPath, coveragePath)
}

/**
 * Run unit tests, publish results and coverage
 *
 * @param projectName Used as suffix for ci:test (e.g. ci:test:foo)
 * @param path The path were the command will be executed.
 * @param resultsPath The path where the JUnit test results can be found. Defaults to "**\/${path}reports/junit-*.xml".
 * @param coveragePath The path where the generated Coberturacoverage can be found. Defaults to "${path}coverage/**\/cobertura-coverage.xml".
 */
public void ciTestProject(String projectName, String path, String resultsPath = null, String coveragePath = null) {
    if (resultsPath == null) {
        resultsPath = "**/${path}reports/**/junit-*.xml"
    }

    if (coveragePath == null) {
        coveragePath = "${path}coverage/**/cobertura-coverage.xml"
    }

    npmRun(path, toCiScript('test', projectName))

    try {
        step([
            $class: 'JUnitResultArchiver',
            testResults: resultsPath
        ])
    } catch (Exception e) {
        echo "Warning: no reports for unit test results found at path ${resultsPath}"
    }

    try {
        step([
            $class: 'CoberturaPublisher',
            coberturaReportFile: coveragePath
        ])
    } catch (Exception e) {
        echo "Warning: no reports for unit test coverage reports found at path ${coveragePath}"
    }
}

/**
 * Run E2E tests, publish results
 *
 * @param path The path were the command will be executed.
 * @param resultsPath The path where the JUnit test results can be found. Defaults to "**\/${path}reports/junit-e2e.xml".
 */
public void ciE2E(String path, String resultsPath = null) {
    ciE2EProject(null, path, resultsPath)
}

/**
 * Run E2E tests, publish results
 *
 * @param projectName Used as suffix for ci:e2e (e.g. ci:e2e:foo)
 * @param path The path were the command will be executed.
 * @param resultsPath The path where the JUnit test results can be found. Defaults to "**\/${path}reports/junit-e2e.xml".
 */
public void ciE2EProject(String projectName, String path, String resultsPath = null) {
    if (resultsPath == null) {
        resultsPath = "**/${path}reports/junit-e2e.xml"
    }

    npmRun(path, toCiScript('e2e', projectName))

    try {
        step([
            $class: 'JUnitResultArchiver',
            testResults: resultsPath
        ])
    } catch (Exception e) {
        echo "Warning: no reports for E2E test results found at path ${resultsPath}"
    }
}

/**
 * Run a specified npm script
 *
 * @param path The path were the command will be executed.
 * @param scriptName The name of the npm script.
 * @param args Optional arguments passed to the script.
 */
public void npmRun(String path, String scriptName, String args = '') {
    npmExecCmd(path, "run ${scriptName}", args ? "-- ${args}" : '')
}

/**
 * Run a specified npm command
 * 
 * @param path The path were the command will be executed.
 * @param command The npm command to execute.
 * @param args Optional arguments passed to the command.
 */
public void npmExecCmd(String path, String command, String args = '') {
    dir(path) {
        sh """
            export CHROME_BIN=/opt/chromium/chromium-latest-linux/latest/chrome
            export NODE_EXTRA_CA_CERTS=/etc/ssl/certs/ca-certificates.crt
            export NO_COLOR=1
            export PATH=${currentNodeVersion}:\\${PATH}
            npm ${command} ${args}
        """
    }
}

private String toCiScript(String scriptName, String projectName) {
    return 'ci:' + scriptName + (projectName ? ":${projectName}" : '')
}


public void ciSemanticRelease(String path) {
    def packageName = getPackageName(path)
    if(!hasSignalIdunaPackageScope(packageName)) {
        throw new IllegalArgumentException("The packageName ${packageName} has invalid scope. Only scope '@signal-iduna' is supported. See https://docs.npmjs.com/cli/v10/using-npm/scope")
    }

    dir(path) {
        withCredentials([
            [$class: 'UsernamePasswordMultiBinding', credentialsId: "jenkins_npmrepo", usernameVariable: 'NEXUS_USER', passwordVariable: 'NEXUS_PASSWORD'],
            [$class: 'UsernamePasswordMultiBinding', credentialsId: "jenkins_git_http", usernameVariable: 'GIT_USER', passwordVariable: 'GIT_PASSWORD']
            ]) {
            
            sh """
                set +x
                echo "
                    email=methoden-standards@signal-iduna.de
                    always-auth=true
                    @signal-iduna:registry=${sdasiRegistry}
                    //${sdasiRegistryUri}:_auth=`echo -n ${NEXUS_USER}:${NEXUS_PASSWORD} | base64`
                   " > .npmrc
               """
        
            sh """
                export GIT_CREDENTIALS=`echo -n ${GIT_USER}:${GIT_PASSWORD}`
                export CHROME_BIN=/opt/chromium/chromium-latest-linux/latest/chrome
                export NODE_EXTRA_CA_CERTS=/etc/ssl/certs/ca-certificates.crt
                export NO_COLOR=1
                export PATH=${currentNodeVersion}:\\${PATH}
                npm run semantic-release
            """
            
            sh """
                   rm .npmrc
               """
        }
    }
}



/**
 * Publish package
 *
 * @param path The path were the command will be executed.
 * @param The tag under which the package will be publish. Leave empty to publish without tag.
 * @param registry The registry URL where the package will be published.
 * @deprecated
 */
public void ciPublish(String path, String tag) {
    def tagArg = tag ? "--tag ${tag}" : ''
    
    dir(path) {
        withCredentials([usernamePassword(credentialsId: 'jenkins_npmrepo', usernameVariable: 'NEXUS_USER', passwordVariable: 'NEXUS_PASSWORD')]) {
            sh """
                set +x
                echo "
                    email=methoden-standards@signal-iduna.de
                    always-auth=true
                    //${sdasiRegistryUri}:_auth=`echo -n ${NEXUS_USER}:${NEXUS_PASSWORD} | base64`
                   " > .npmrc
               """

            npmExecCmd('./', 'publish', "--registry=${sdasiRegistry} ${tagArg}")

            sh """
                rm .npmrc
            """
        }
    }
}

/**
 * Checks whether the given package name begins with "@signal-iduna"
 *
 * @param packageName The name of the package to be checked.
 * @return true, if the packageName begins with "@signal-iduna", else false.
 */
private hasSignalIdunaPackageScope(String packageName) {
    return packageName.toLowerCase().startsWith("@signal-iduna");
}

/**
 * Build frontend in given path.
 * @deprecated
 * @param path - the path where the frontend relies.
 */
void build(String path) {
    printDeprecationWarning('si_npm.build', 'si_npm.ciInstall and si_npm.ciBuild')

    dir(path) {
        sh """
            export PATH=$currentNodeVersion:\\$PATH
            export NODE_EXTRA_CA_CERTS=/etc/ssl/certs/ca-certificates.crt
            npm install
            npm run build
        """
    }
}

/**
 * Test und check the frontend in the given path.
 * @deprecated
 * @param path - the path where the frontend relies.
 */
void check(String path) {
    printDeprecationWarning('si_npm.check', 'si_npm.ciTest and si_npm.ciLint')

    test(path, true)
    lint(path, true)
}

/**
 * Test the frontend in the given path.
 * @deprecated
 * @param path - the path where the frontend relies.
 */
void test(String path, hideDeprecationWarning = false) {
    if (!hideDeprecationWarning) {
        printDeprecationWarning('si_npm.test', 'si_npm.ciTest')
    }

    dir(path) {
        sh """
            export CHROME_BIN=/opt/chromium/chromium-latest-linux/latest/chrome
            export PATH=$currentNodeVersion:\\$PATH
            export NODE_EXTRA_CA_CERTS=/etc/ssl/certs/ca-certificates.crt
            npm run test
        """
    }
    publishHTML(target: [
            allowMissing         : false,
            alwaysLinkToLastBuild: false,
            keepAll              : true,
            reportDir            : "$path/coverage",
            reportFiles          : 'index.html',
            reportName           : 'Testabdeckung (Frontend)'
    ])
}

/**
 * Check the frontend in the given path.
 * @deprecated
 * @param path - the path where the frontend relies.
 */
void lint(String path, hideDeprecationWarning = false) {
    if (!hideDeprecationWarning) {
        printDeprecationWarning('si_npm.lint', 'si_npm.ciLint')
    }

    dir (path) {
        sh """
            export PATH=$currentNodeVersion:\\$PATH
            export NODE_EXTRA_CA_CERTS=/etc/ssl/certs/ca-certificates.crt
            npm run lint:checkstyle
        """
    }
    step([$class: 'hudson.plugins.checkstyle.CheckStylePublisher', pattern: '**/checkstyle*.xml', unstableTotalAll: '0'])
}

void printDeprecationWarning(String deprecatedFunction, String replacement) {
    echo "┳┻|\n┻┳|\n┳┻| _     HEADS UP! The ${deprecatedFunction} function in your Jenkinsfile is deprecated.\n┻┳| •.•)\n┳┻|⊂ﾉ     Please use ${replacement} instead.\n┻┳|\n┳┻|"
}

/*
 * Method to call node.js directly
 *
 * @param path Folder to set working directory
 * @param command node.js based program
 * @param args Optional arguments to the command
 */
public void nodeExecCmd(String path, String command, String args = '') {
    dir(path) {
        sh """
            export CHROME_BIN=/opt/chromium/chromium-latest-linux/latest/chrome
            export NODE_EXTRA_CA_CERTS=/etc/ssl/certs/ca-certificates.crt
            export PATH=${currentNodeVersion}:\\${PATH}
            node ${command} ${args}
        """
    }
}

/**
 * Runs a sonar scan via npm, publishes results to the sonarqube server.
 * IMPORTANT: Assumes that tests have been run previously for coverage
 *
 * @param path - the path where the frontend relies.
 * @param coveragePath The path where the coverage results can be found. Defaults to "${path}coverage/lcov.info".
 */
void staticAnalysis(String folder, String coveragePath="coverage/lcov.info", boolean failBuildOnQualityGate = false, String additionalConfiguration = "") {
    dir(folder) {
        def repo = si_git.parseGitUrl()
        def pr = si_git.getPullRequest(repo)
        if (pr != null) {
            sonarPr(folder, coveragePath, failBuildOnQualityGate, repo, pr, additionalConfiguration)
        } else {
            sonarCurrentBranch(folder, coveragePath, failBuildOnQualityGate, repo, additionalConfiguration)
        }
    }
}

/**
 * Executes a sonar pull request analysis and reports results to bitbucket
 */
void sonarPr(String folder, String coveragePath, boolean failBuildOnQualityGate, BitbucketRepo repo, BitbucketPr pr, String additionalConfiguration) {
   withSonarQubeEnv("SDA-SonarQube") { // Will pick the global server connection you have configured
        npmRun(folder, 'sonar-scanner',
                "-Dsonar.qualitygate.wait=${failBuildOnQualityGate} \
                -Dsonar.scm.provider=git \
                -Dsonar.projectKey=${repo.projectName}_${repo.repoName}_frontend \
                -Dsonar.projectName=${repo.projectName}_${repo.repoName}_frontend \
                -Dsonar.javascript.lcov.reportPaths=${coveragePath} \
                -Dsonar.pullrequest.key=${pr.id} \
                -Dsonar.pullrequest.base=${pr.target} \
                -Dsonar.pullrequest.branch=${env.BRANCH_NAME} \
                -Dsonar.pullrequest.provider=bitbucketserver \
                -Dsonar.pullrequest.bitbucketserver.project=${repo.projectName} \
                -Dsonar.pullrequest.bitbucketserver.repository=${repo.repoName} \
                ${additionalConfiguration} \
            ")
    }
}

/**
 * Executes a sonar analysis on the current branch
 */
void sonarCurrentBranch(String folder, String coveragePath, boolean failBuildOnQualityGate, BitbucketRepo repo, String additionalConfiguration) {
    withSonarQubeEnv("SDA-SonarQube") { // Will pick the global server connection you have configured
        npmRun(folder, 'sonar-scanner',
                "-Dsonar.qualitygate.wait=${failBuildOnQualityGate} \
                -Dsonar.scm.provider=git \
                -Dsonar.projectKey=${repo.projectName}_${repo.repoName}_frontend \
                -Dsonar.projectName=${repo.projectName}_${repo.repoName}_frontend \
                -Dsonar.branch.name=${env.BRANCH_NAME} \
                -Dsonar.javascript.lcov.reportPaths=${coveragePath} \
                ${additionalConfiguration} \
            ")
    }
}

/*
 * returns the name of the package by reading the package.json.
 *
 * @param path The path were the command will be executed.
* @return the name of the package.
 */
private getPackageName(String folder) {
    dir(folder) {
        def props = readJSON file: 'package.json'

        return props.name
    }
}
