import de.signaliduna.BitbucketPr
import de.signaliduna.BitbucketRepo
import groovy.json.JsonSlurperClassic

import java.time.LocalDate
import java.time.format.DateTimeFormatter

@groovy.transform.Field
def MASTER = 'master'
@groovy.transform.Field
def MAIN = 'main'
@groovy.transform.Field
def DEVELOP = 'develop'
@groovy.transform.Field
def FEATURE_PREFIX = 'feature/'
@groovy.transform.Field
def RELEASE_PREFIX = 'release'
@groovy.transform.Field
def RENOVATE_PREFIX = 'renovate/'
@groovy.transform.Field
def VERSION_PATTERN = /^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-(0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*)(\.(0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*))*)?(\+[0-9a-zA-Z-]+(\.[0-9a-zA-Z-]+)*)?$/

/**
 * Determine if you are on the master branch
 *
 * @return true if you are on master, else false.
 */
public boolean isMaster() {
    return isBranch(MASTER)
}

/**
 * Determine if you are on the main branch
 *
 * @return true if you are on main, else false.
 */
public boolean isMain() {
    return isBranch(MAIN)
}

/**
 * Determine if you are on the main or master branch
 *
 * @return true if you are on main or master, else false.
 */
public boolean isMainOrMaster() {
    return isMain() || isMaster()
}

/**
 * Determine if you are on the develop branch
 *
 * @return true if you are on develop, else false.
 */
public boolean isDevelop() {
    return isBranch(DEVELOP)
}

/**
 * Determine if you are on a feature branch
 *
 * @return true if you are on feature/*, else false.
 */
public boolean isFeature() {
    return branchName().startsWith(FEATURE_PREFIX)
}

/**
 * Determine if you are on a release branch
 *
 * @return true if you are on release/*, else false.
 */
public boolean isRelease() {
    return branchName().startsWith(RELEASE_PREFIX)
}

/**
 * Determine if you are on a renovate branch
 *
 * @return true if you are on renovate/*, else false.
 */
public boolean isRenovate() {
    return branchName().startsWith(RENOVATE_PREFIX)
}

/**
 * Determine if the current commit is tagged with the given tag name
 *
 * Does not work when the commit has multiple tags.
 *
 * @param tagName The tag name to check against.
 * @return true if the tag of the current commit matches the given tag name, else false.
 */
public boolean hasTag(String tagName) {
    return currentCommitTags().any { tag -> tag == tagName }
}

/**
 * Determines if the current commit has a tag matching v0.0.0
 *
 * Does not work when the commit has multiple tags.
 */
public boolean hasVersionTag() {
    return currentCommitTags().any { tag -> tag ==~ VERSION_PATTERN }
}

/**
 * Get the commit ID
 *
 * @return The commit ID.
 */
String commitId() {
    return sh(
            returnStdout: true,
            script: 'git rev-parse HEAD'
    ).trim()
}

/**
 * Get the trimmed short commit ID
 *
 * @return The short commit ID.
 */
public String shortCommitId() {
    return sh(
            returnStdout: true,
            script: 'git rev-parse --short HEAD'
    ).trim()
}

/**
 * Get the tags of the current commit
 *
 * @return The current commit tags.
 */
public String[] currentCommitTags() {
    return sh(
            returnStdout: true,
            script: 'git tag -l --points-at HEAD'
    ).trim().split('\n')
}

/**
 * Get the branch name from the environment variables
 *
 * @return The branch name.
 */
public String branchName() {
    return env.BRANCH_NAME
}

/**
 * Get the feature name from the branch name environment variable
 *
 * @returns The feature name or empty string if not on a feature branch.
 */
public String featureName() {
    if (!isFeature()) {
        return ''
    }

    return branchName().substring(FEATURE_PREFIX.length())
}

/**
 * Push commits to target branch, follow tags
 */
public void push(String targetBranch) {
    sh "git push --follow-tags origin ${targetBranch}"
}

/**
 * Check out the given branch via git checkout
 *
 * @param The branch name to checkout from.
 */
public void checkoutBranch(String name, Map notificationConfig = [:]) {
    checkout scm

    sh "git fetch -a -p"

    if (branchExistsLocally(name)) {
        // detach from branch, so we can delete it
        sh "git checkout origin/${name}"
        // delete branch
        sh "git branch -D ${name}"
    }

    sh "git checkout ${name}"

    if (isMaster()) {
        // git by default checks out detached, we need a local branch
        // workaround for https://issues.jenkins-ci.org/browse/JENKINS-31924
        sh 'git fetch --prune origin +refs/tags/*:refs/tags/*' // delete all local tags
        sh 'git reset --hard origin/master'
        sh 'git clean -ffdx'
    } else {
        sh 'git fetch --tags -f'
        sh 'git clean -ffd'
    }

    si_jenkins.notifyInProgress(notificationConfig)
}

private branchExistsLocally(String name) {
    result = sh(returnStdout: true, script: "git branch --list ${name}")
    return result != ''
}

/**
 * Check out the given branch via git checkout to a given commitHash
 *
 * @param branchName The branch name to checkout from.
 * @param commitHash The commitHash you want to checkout.
 */
public void checkoutWithCommitHash(String branchName, String commitHash) {
    checkoutBranch(branchName)
    sh "git reset --hard ${commitHash}"
    sh "git clean -ffd"
}

/**
 * @return the email of the current commit.
 */
public String emailOfLastCommit() {
    return sh(
            returnStdout: true,
            script: "git log -1 --pretty=format:'%ae'"
    ).trim()
}

/**
 * @return the message of the last commit.
 */
public String lastCommitMessage() {
    return sh(
            returnStdout: true,
            script: "git log -1 --pretty=format:'%B'"
    ).trim()
}

/**
 * extracting jira ticket from commit messages
 *
 * @param jiraProjektPrefix e.g. the 'CSPW' part of 'CSPW-87'
 * @return
 */
public extractJiraReferenceFromCommit(String jiraProjektPrefix) {
    jiraProjektPrefix = jiraProjektPrefix + "-"

    String commitMessage = lastCommitMessage()
    println "Extracting jira ticket reference from commit message of last commit: ${commitMessage}"

    String lowerCaseMessage = commitMessage.toLowerCase()

    int prefixStart = lowerCaseMessage.indexOf(jiraProjektPrefix.toLowerCase())

    if (prefixStart > -1) {
        def shortenedMessage = lowerCaseMessage.substring(prefixStart + jiraProjektPrefix.length());
        def number = shortenedMessage.replaceAll("\\D", "").trim()
        return jiraProjektPrefix + number
    }

    println("failed to identify a jira reference for the group prefix: " + jiraProjektPrefix)
}

/**
 * @return last commit id of global pipeline library named si-dp-shared-libs.
 */
public String lastCommitIdOfSharedLibs() {
    try {
        return sh(
                returnStdout: true,
                script: """
                git ls-remote -h -- ssh://git@git.system.local:7999/sda/jenkins_shared_libraries | grep refs/heads/master | cut -f 1
            """).trim()
    } catch (Exception e) {
        println "Commit-ID of shared library could not be found. Exception was ${e.getMessage()}"
        return ""
    }
}


/**
 * @return A de.signaliduna.BitbucketPr or null if there are no open pull requests for the current branch
 */
public BitbucketPr getPullRequest(BitbucketRepo repo) {
    def bitbucketApi = httpRequest url: "https://git.system.local/rest/api/1.0/projects/${repo.projectName}/repos/${repo.repoName}/pull-requests?at=refs/heads/${env.BRANCH_NAME}&direction=OUTGOING&state=OPEN", authentication: "jenkins_git_http", validResponseCodes: '200'

    def pr = new JsonSlurperClassic().parseText(bitbucketApi.content)

    if (pr.size < 1) {
        return null //no open pull request found
    }

    if (pr.size > 1) {
        echo "Found more than one pull requests - falling back to the first one returned from the API"
    }

    return mapToBitbucketPr(pr.values[0])
}

public List<BitbucketPr> getOpenPullRequestsToMaster(BitbucketRepo repo) {
    def bitbucketApi = httpRequest url: "https://git.system.local/rest/api/1.0/projects/${repo.projectName}/repos/${repo.repoName}/pull-requests?at=refs/heads/master&direction=INCOMING&state=OPEN", authentication: "jenkins_git_http", validResponseCodes: '200'

    def response = new JsonSlurperClassic().parseText(bitbucketApi.content)

    return response.values.collect { pr -> mapToBitbucketPr(pr) }
}

private BitbucketPr mapToBitbucketPr(def json) {
    return new BitbucketPr(
            json.id.toString(),
            json.toRef.id.replaceAll("refs\\/heads\\/", "")
    )
}

public BitbucketRepo parseGitUrl() {
    def url = sh(script: 'git remote get-url origin', returnStdout: true)

    def matcher = url.startsWith('ssh://') ? matchSshUrl(url) : matchHttpUrl(url)

    return new BitbucketRepo(
            //index [0][0] is full match, [0][1] is first group in first match
            matcher[0][1],
            matcher[0][2]
    )
}

private matchSshUrl(url) {
    return url =~ /7999\/(.*?)\/(.*?)\.git/
}

private matchHttpUrl(url) {
    return url =~ /scm\/(.*?)\/(.*?)\.git/
}

private isBranch(String name) {
    return branchName() == name;
}


/**
 * Clones the given repository via git clone
 *
 * @param the repo URL to clone from.
 */
public void alpha_clone(String repoUrl, String branch, String serviceFolder) {
    sh """
       git clone ${repoUrl}
       cd ${serviceFolder}
       git checkout ${branch}
    """
}

public void alpha_addAndPush(String message) {
    sh """
        git add --all 
        git commit -m "${message}"
        git push
    """
}

/**
 * Creates a git tag with the given prefix and the actual date.
 * Example with tagPrefix 'foo': foo-2025-01-23
 * Example without tagPrefix: 2025-01-23
 *
 * @param tagPrefix a optional prefix for the git tag. Without this, the tag will be the local date
 */
public void createGitTag(String tagPrefix = "") {
    def now = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    sh "git tag -f ${tagPrefix != "" ? tagPrefix + "-" : ""}$now"
    sh "git show-ref --abbrev=11 --tags"
    sh "git push -f --tags"
}
