import de.signaliduna.BitbucketRepo
import de.signaliduna.CopsiEnvironment
import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic
import groovy.transform.Field

@Field
String CERT_FILE_NOP = "https://git.system.local/projects/CP/repos/sealed-secrets-keys/raw/public-key-nop?at=refs%2Fheads%2Fmain"

@Field
String CERT_FILE_PRD = "https://git.system.local/projects/CP/repos/sealed-secrets-keys/raw/public-key-prd?at=refs%2Fheads%2Fmain"

@Field
String GIT_CLONE_DIR = "cd-repos"

@Field
String GIT_CREDENTIALS_ID = "jenkins_git_http"

@Field
String BITBUCKET_REST_API = "https://git.system.local/rest/api/1.0/projects"

@Field
String BITBUCKET_REST_BRANCH_UTILS = "https://git.system.local/rest/branch-utils/1.0/projects"

/**
 * Gibt den Pfad zur Zertifikatsdatei für kubeseal zurück.
 *
 * Die Funktion gibt den Pfad zur Zertifikatsdatei zurück, die zum Verschlüsseln von Secrets für den angegebenen Cluster verwendet wird.
 * Für den prd-Cluster wird eine andere Zertifikatsdatei verwendet als für den nop-Cluster. Die Zertifikate werden aus dem
 * GIT-Repository https://git.system.local/projects/CP/repos/sealed-secrets-keys geladen.
 *
 * @param clusterName Der Name des Clusters. Folgende Cluster sind bekannt: prd, nop.
 *
 * @return Der Pfad zur Zertifikatsdatei.
 *
 * @example
 * ```groovy
 * String nopCertFile = si_copsi.getSealedSecretCertFile(CopsiEnvironment.nop)
 * String prdCertFile = si_copsi.getSealedSecretCertFile(CopsiEnvironment.prd)
 * ```
 */
String getSealedSecretCertFile(CopsiEnvironment clusterName) {
    if (clusterName == CopsiEnvironment.prd) {
        return CERT_FILE_PRD
    }

    return CERT_FILE_NOP
}

/**
 * @param clusterName Der Name des Clusters. Folgende Cluster sind bekannt: prd, nop.
 * @return Der Pfad zur Zertifikatsdatei.
 * @deprecated Nutze stattdessen getSealedSecretCertFile(CopsiEnvironment clusterName)
 */
String getSealedSecretCertFile(String clusterName) {
    return getSealedSecretCertFile(CopsiEnvironment.valueOf(clusterName))
}

/**
 * Schreibt eine SealedSecret-Datei.
 *
 * Diese Funktion erstellt ein Kubernetes Secret, verschlüsselt es mit kubeseal und schreibt das SealedSecret in eine Datei.
 * Die Funktion verwendet das angegebene Zertifikat zum Verschlüsseln des Secrets. Das Secret wird mit den Annotationen
 * `sealedsecrets.bitnami.com/managed: true` und `sealedsecrets.bitnami.com/namespace-wide: true` versehen.
 *
 * @param config Eine Map mit den folgenden Konfigurationsparametern:
 *  * `name` (String): Der Name des Secrets.
 *  * `namespace` (String): Der Namespace des Secrets.
 *  * `secretData` (Map): Eine Map mit den Secret-Daten. Die Schlüssel der Map sind die Namen der Secret-Einträge, die Werte sind die Werte der Secret-Einträge als Strings.
 *  * `certFile` (String): Der Pfad zur Zertifikatsdatei, die zum Verschlüsseln des Secrets verwendet wird.
 *  * `outputFile` (String): Der relative Pfad (im WORKSPACE) zur Ausgabedatei, in die das verschlüsselte Secret geschrieben wird.
 *  * `prefix` (String): Ein Prefix, der an den Anfang der Datei geschrieben wird. So kann z.B. ein Kommentar in die Datei geschrieben werden, dass diese generiert wurde.
 *
 * @throws Exception Wenn ein Fehler beim Schreiben der Datei oder beim Verschlüsseln des Secrets auftritt.  Dies kann beispielsweise der Fall sein, wenn kubeseal nicht verfügbar ist oder das Zertifikat ungültig ist.
 *
 * @example
 * ```groovy
 * String writeBkvanSealedSecretFile(String outputFile, CopsiEnvironment clusterName, String targetSegment) {
 *     withCredentials([
 *             usernamePassword(credentialsId: 'moonlab-s3-credentials-' + targetSegment, usernameVariable: 'accesskey', passwordVariable: 'secretkey'),
 *             usernamePassword(credentialsId: 'moonlab-oauth-s-user-' + targetSegment, usernameVariable: 'secureUser', passwordVariable: 'securePassword'),
 *             usernamePassword(credentialsId: 'moonlab-pdc-credentials', usernameVariable: 'pdcuser', passwordVariable: 'pdcpassword'),
 *     ]) {
 *         si_copsi.writeSealedSecretFile([
 *                 name      : 'bkvanbackend-secret',
 *                 namespace : 'moonlab-platform',
 *                 secretData: [
 *                     'S3_ACCESS_KEY'          : accesskey,
 *                     'S3_SECRET_KEY'          : secretkey,
 *                     'CCF_USERNAME'           : secureUser,
 *                     'CCF_PASSWORD'           : securePassword,
 *                     'PDC_USERNAME'           : pdcuser,
 *                     'PDC_PASSWORD'           : pdcpassword,
 *                 ],
 *                 certFile  : si_copsi.getSealedSecretCertFile(clusterName),
 *                 outputFile: outputFile,
 *                 prefix: '# This file has been generated'
 *         ])
 *     }
 * }
 *
 * writeBkvanSealedSecretFile('envs/overlays/tst/bkvanbackend/secrets.yaml', CopsiEnvironment.nop, 'tst')
 * writeBkvanSealedSecretFile('envs/overlays/abn/bkvanbackend/secrets.yaml', CopsiEnvironment.nop, 'abn')
 * writeBkvanSealedSecretFile('envs/overlays/prd/bkvanbackend/secrets.yaml', CopsiEnvironment.prd, 'prd')
 * ```
 */
void writeSealedSecretFile(Map config) {
    def secretContent = [
            'apiVersion': 'v1',
            'kind': 'Secret',
            'metadata': [
                    'name': config.name,
                    'namespace': config.namespace,
                    'annotations': [
                            'sealedsecrets.bitnami.com/managed': 'true',
                            'sealedsecrets.bitnami.com/namespace-wide': 'true'
                    ]
            ],
            'data': config.secretData.collectEntries { entry -> ["${entry.key}": entry.value.bytes.encodeBase64().toString()] }
    ]
    String prefix = config.containsKey("prefix") ? config.prefix : "# This file has been automatically generated by jenkins_shared_libraries module si_copsi"
    prefix = prefix?.trim() ? "$prefix\n" : ""

    withEnv(["PATH=${PATH}:/tools"]) {
        String secretAsYaml = writeYaml data: secretContent, returnText: true
        String sealedSecretAsYaml = sh script: "echo '${secretAsYaml}' | kubeseal --format yaml --cert='${config.certFile}'", returnStdout: true

        String outputFolder = new File(config.outputFile).getParent()
        sh "mkdir -p ${outputFolder}"
        writeFile file: config.outputFile, text: prefix + sealedSecretAsYaml, encoding: "UTF-8"
    }
}

/**
 * Erstellt eine Änderung in einem sda-service-deployments Git-Repository.  Dies ist nützlich, um beispielsweise
 * Image-Tags in Kubernetes Deployments zu aktualisieren.
 *
 * Die Funktion klont das Repository, führt die übergebene Closure aus, um Änderungen vorzunehmen (z.B. Image-Tag aktualisieren),
 * und pusht die Änderungen zurück zum Repository, falls welche vorhanden sind.
 *
 * Das Repository wird unter im Ordner cd-repos (überschreibbar mittels GIT_CLONE_DIR) des Workspaces abgelegt.
 * Dieser sollte zu der .gitignore etc. hinzugefügt werden.
 *
 * @param repo Ein `BitbucketRepo`-Objekt, das den Projektnamen und Repository namen enthält.
 * @param targetBranch Ein branch-Name, auf dem die Änderung commited & gepusht werden soll.
 * @param updater Eine Closure, die die Aktualisierungen im geklonten Repository vornimmt.  Diese Closure erhält keine Argumente
 *               und sollte einen String zurückgeben, der als Commit-Nachricht verwendet wird.  Innerhalb der Closure
 *               kann mit Shell-Befehlen und Groovy-Code gearbeitet werden, um die gewünschten Änderungen durchzuführen.
 *               **Wichtig:** Die Closure wird innerhalb des geklonten Repositorys ausgeführt.
 * @return `true`, wenn Änderungen gepusht wurden, `false` wenn keine Änderungen vorhanden waren.
 *
 * @throws org.jenkinsci.plugins.workflow.steps.FlowInterruptedException Falls der Git-Clone, Commit oder Push fehlschlägt.
 *
 * @example
 * ```groovy
 * import de.signaliduna.BitbucketRepo
 * import de.signaliduna.CopsiEnvironment
 * import de.signaliduna.TargetSegment
 *
 * String appBackendFolder = "path/to/your/backend/app" // Pfad zu Ihrer Backend-Applikation anpassen
 * String image = si_docker.buildImageWithDockerfile(appBackendFolder, "$appBackendFolder/DockerfileCoPlat", "moonlab", "platform", "bkvanbackend", TargetSegment.tst)
 *
 * si_copsi.createChange(new BitbucketRepo("SDASVCDEPLOY", "moonlab-platform"), "${CopsiEnvironment.nop}", {
 *     // NOTE: Wir befinden uns innerhalb des frisch geklonten Repositorys.
 *     dir("envs/overlays/tst") {
 *         if (fileExists("kustomization.yaml")) {
 *             sh "/tools/kustomize edit set image bkvanbackend-image=${image}"
 *             sh "git add kustomization.yaml"
 *         }
 *     }
 *
 *     String jiraTicket = "MOONLAB-16925"
 *     return "update image tag\n\n${image}\n\n${jiraTicket}"
 * })
 * ```
 */
boolean createChange(BitbucketRepo repo, String targetBranch, Closure<String> updater) {
    sh "mkdir -p ${GIT_CLONE_DIR}"
    dir(GIT_CLONE_DIR) {
        withCredentials([gitUsernamePassword(credentialsId: GIT_CREDENTIALS_ID)]) {
            def gitUrl = buildGitUrl("https://git.system.local/scm/${repo.projectName}/${repo.repoName}.git")
            sh "rm -rf ${repo.repoName}"
            sh "git clone ${gitUrl} --depth 1 -b '${targetBranch}'"
            dir(repo.repoName) {
                String commitMessage = updater()
                sh "git commit --allow-empty -m '${commitMessage}'"

                String changedFiles = sh(script: "git diff --name-only ${targetBranch} origin/${targetBranch}", returnStdout: true).trim()
                if (changedFiles) {
                    echo "The following files changed:"
                    echo "${changedFiles}"

                    sh 'git push -u origin HEAD'
                    return true
                } else {
                    echo "No changes between the local and remote branches."
                    return false
                }
            }
        }
    }
}

/**
 * Erstellt einen Pull Request in Bitbucket für die angegebenen Änderungen.
 *
 * Klont das angegebene Repository, erstellt einen neuen Branch, führt die übergebene Closure aus, um Änderungen vorzunehmen
 * (z.B. Image-Tag aktualisieren), committet die Änderungen und pusht den Branch. Anschließend wird ein Pull Request erstellt.
 *
 * @param repo Das Bitbucket Repository.
 * @param targetBranch Der Ziel-Branch für den Pull Request.
 * @param pullRequestAttributes Zusätzliche Attribute für den Pull Request (z.B. title, description, reviewers).
 *              See https://developer.atlassian.com/cloud/bitbucket/rest/api-group-pullrequests/#api-repositories-workspace-repo-slug-pullrequests-post
 * @param updater Ein Closure, der die Änderungen am Repository vornimmt. Der Closure erhält keinen Parameter.
 *               Innerhalb des Closures befindet man sich im root Verzeichnis des geklonten Repositorys.
 *               Der Rückgabewert des Closures wird als Commit-Message verwendet.
 * @return Die ID des erstellten Pull Requests oder ein leerer String, wenn kein Pull Request erstellt wurde (z.B. keine Änderungen).
 *
 * @example
 * ```groovy
 * import de.signaliduna.BitbucketRepo
 * import de.signaliduna.CopsiEnvironment
 * import de.signaliduna.TargetSegment
 *
 * String appBackendFolder = "path/to/your/backend/app" // Pfad zu Ihrer Backend-Applikation anpassen
 * String image = si_docker.buildImageWithDockerfile(appBackendFolder, "\$appBackendFolder/DockerfileCoPlat", "moonlab", "platform", "bkvanbackend", TargetSegment.abn)
 *
 * si_copsi.createChangeAsPullRequest(new BitbucketRepo("SDASVCDEPLOY", "moonlab-platform"), "${CopsiEnvironment.prd}", [:], {
 *     // NOTE: Wir befinden uns innerhalb des frisch geklonten Repositorys.
 *     dir("envs/overlays/prd") {
 *         if (fileExists("kustomization.yaml")) {
 *             sh "/tools/kustomize edit set image bkvanbackend-image=${image}"
 *             sh "git add kustomization.yaml"
 *         }
 *     }
 *
 *     String jiraTicket = "MOONLAB-16925"
 *     return "update image tag\n\n\${image}\n\n\${jiraTicket}"
 * })
 * ```
 */
String createChangeAsPullRequest(BitbucketRepo repo, String sourceBranch = "autodeploy/job-${BUILD_NUMBER}", String targetBranch, Map pullRequestAttributes, Closure<String> updater) {
    sh "mkdir -p ${GIT_CLONE_DIR}"
    String prId = ""
    dir(GIT_CLONE_DIR) {
        withCredentials([gitUsernamePassword(credentialsId: GIT_CREDENTIALS_ID)]) {
            def gitUrl = buildGitUrl("https://git.system.local/scm/${repo.projectName}/${repo.repoName}.git")
            sh "rm -rf ${repo.repoName}"
            sh "git clone ${gitUrl} --depth 1 -b '${targetBranch}'"
            dir(repo.repoName) {
                sh "git checkout -b '${sourceBranch}'"
                String commitMessage = updater()
                sh "git commit --allow-empty -m '${commitMessage}'"

                String changedFiles = sh(script: "git diff --name-only ${sourceBranch} origin/${targetBranch}", returnStdout: true).trim()
                if (changedFiles) {
                    echo "The following files changed:"
                    echo "${changedFiles}"

                    sh 'git push -u origin HEAD'
                    prId = createPullRequest(repo, sourceBranch, targetBranch, pullRequestAttributes)
                } else {
                    echo "No changes between the local and remote branches."
                }
            }
        }
    }

    return prId
}

String createPullRequest(BitbucketRepo repo, String sourceBranch, String targetBranch, Map pullRequestAttributes) {
    def defaultPullRequestAttributes = [
        title: "Autodeploy ${BUILD_NUMBER}",
        description: "Autodeploy for build number ${BUILD_NUMBER}"
    ]

    def fixedPullRequestAttributes = [
        fromRef: [
            id: sourceBranch,
            repository: [
                    slug: repo.repoName,
                    name: null,
                    project: [
                            key: "${repo.projectName}"
                    ]
            ]
        ],
        toRef: [
            id: targetBranch,
            repository: [
                    slug: repo.repoName,
                    name: null,
                    project: [
                            key: "${repo.projectName}"
                    ]
            ]
        ],
        close_source_branch: true
    ]

    def requestBody = defaultPullRequestAttributes + pullRequestAttributes + fixedPullRequestAttributes


    def requestBodyAsJson = JsonOutput.toJson(requestBody)
    def request = httpRequest httpMode: 'POST', url: "${BITBUCKET_REST_API}/${repo.projectName}/repos/${repo.repoName}/pull-requests", authentication: GIT_CREDENTIALS_ID, validResponseCodes: '201', contentType: "APPLICATION_JSON", requestBody: requestBodyAsJson
    def response = new JsonSlurperClassic().parseText(request.content)

    return response.id
}

/**
 * Waits for merge checks to pass and then merges the specified pull request.
 *
 * @param repo The Bitbucket repository.
 * @param prId The ID of the pull request.
 * @param abortBuildOnError Set if the jenkins build should abort on merge errors. Defaults to false.
 * @param deleteSourceBranch Set if source branch should be deleted. Defaults to true.
 * @param maxAttempts The maximum number of attempts to check the merge status before timing out. Defaults to 30.
 * @return True if the pull request was merged successfully, false otherwise.
 *
 *  * @example
 * ```groovy
 * import de.signaliduna.BitbucketRepo
 *
 * String prId = si_copsi.createChangeAsPullRequest(new BitbucketRepo("SDASVCDEPLOY", "<repository>"),...)
 * si_copsi.waitForMergeChecksAndMerge(new BitbucketRepo("SDASVCDEPLOY", "<repository>"), prId)
 * ```
 */
boolean waitForMergeChecksAndMerge(BitbucketRepo repo, String prId, boolean abortBuildOnError = false, boolean deleteSourceBranch = true, int maxAttempts = 30) {
    sleep(time: 5, unit: "SECONDS")

    int attempts = 0

    while (attempts < maxAttempts) {
        def prStatus = getPullRequestMergeStatus(repo, prId)

        if (prStatus == null) {
            if (abortBuildOnError) {
                error("Error retrieving PR information for PR ${prId}")
            }
            return false
        }

        if (prStatus.canMerge) {
            def prInfo = getPullRequestInfo(repo, prId)
            def mergeResponse = mergePullRequest(repo, prId, prInfo.version as String, deleteSourceBranch)
            if (mergeResponse != null) {
                return true
            } else {
                if (abortBuildOnError) {
                    error("Error merging PR ${prId}")
                }
                return false
            }
        }

        sleep(time: 1, unit: "SECONDS")
        attempts++
    }

    if (abortBuildOnError) {
        currentBuild.result = "ABORTED"
        error("Timeout waiting for merge conditions for PR ${prId}")
    }
    return false
}

/**
 * Retrieves the merge status of a pull request.
 *
 * @param repo The Bitbucket repository.
 * @param prId The ID of the pull request.
 * @return A map representing the merge status, or null if an error occurs.
 */

Map getPullRequestMergeStatus(BitbucketRepo repo, String prId) {
    GString url = "${BITBUCKET_REST_API}/${repo.projectName}/repos/${repo.repoName}/pull-requests/${prId}/merge"
    try {
        def request = httpRequest httpMode: 'GET', url: url, authentication: GIT_CREDENTIALS_ID, validResponseCodes: '200'
        return new JsonSlurperClassic().parseText(request.content)
    } catch (Exception ex) {
        println("Error retrieving ${url}: ${ex.message}")
        return null
    }
}

/**
 * Retrieves information about a pull request.
 *
 * @param repo The Bitbucket repository.
 * @param prId The ID of the pull request.
 * @return A map representing the pull request information, or null if an error occurs.
 */
Map getPullRequestInfo(BitbucketRepo repo, String prId) {
    GString url = "${BITBUCKET_REST_API}/${repo.projectName}/repos/${repo.repoName}/pull-requests/${prId}"
    try {
        def request = httpRequest httpMode: 'GET', url: url, authentication: GIT_CREDENTIALS_ID, validResponseCodes: '200'
        return new JsonSlurperClassic().parseText(request.content)
    } catch (Exception ex) {
        println("Error retrieving ${url}: ${ex.message}")
        return null
    }
}

/**
 * Merges a pull request.
 *
 * @param repo The Bitbucket repository.
 * @param prId The ID of the pull request.
 * @param version The version of the pull request.
 * @param deleteSourceBranch Set if source branch should be deleted.
 * @return The ID of the merged pull request, or null if an error occurs.
 */
String mergePullRequest(BitbucketRepo repo, String prId, String version, boolean deleteSourceBranch) {
    GString url = "${BITBUCKET_REST_API}/${repo.projectName}/repos/${repo.repoName}/pull-requests/${prId}/merge"
    def requestBody = [context: [], version: version]
    def requestBodyAsJson = JsonOutput.toJson(requestBody)

    try {
        def request = httpRequest(httpMode: 'POST', url: url, authentication: GIT_CREDENTIALS_ID, validResponseCodes: '200', contentType: "APPLICATION_JSON", requestBody: requestBodyAsJson)
        def response = new JsonSlurperClassic().parseText(request.content)

        if (deleteSourceBranch) {
            def prInfo = getPullRequestInfo(repo, prId)
            GString deleteUrl = "${BITBUCKET_REST_BRANCH_UTILS}/${repo.projectName}/repos/${repo.repoName}/branches"
            def deleteRequestBody = [name: prInfo.fromRef.id, dryRun: false]
            def deleteRequestBodyAsJson = JsonOutput.toJson(deleteRequestBody)
            httpRequest(httpMode: 'DELETE', url: deleteUrl, authentication: GIT_CREDENTIALS_ID, validResponseCodes: '204', contentType: "APPLICATION_JSON", requestBody: deleteRequestBodyAsJson)
        }

        return response.id
    } catch (Exception ex) {
        println("Error merging ${url}: ${ex.message}")
        return null
    }
}

/**
 * Builds a Git URL, optionally including encoded credentials,
 * depending on whether the current Jenkins instance matches known legacy setups (i.e. ci.system.local/[...]).
 * <p>
 * This method is intended for compatibility with older Jenkins servers
 * that require credentials embedded in the Git URL (e.g., https://user:pass@...),
 * while avoiding credential injection on more recent, centrally managed Jenkins instances
 * (i.e. sda-jenkins.system.local) where authentication is handled implicitly.
 * <p>
 * Git credentials must be provided via Jenkins' credentials binding using
 * {@code gitUsernamePassword(...)} inside a {@code withCredentials} block.
 * The standard environment variables {@code GIT_USERNAME} and {@code GIT_PASSWORD}
 * will be used and URL-encoded to prevent issues with special characters.
 *
 * @param gitUrl The original HTTPS Git repository URL (without credentials).
 * @return The modified URL with embedded credentials if needed; otherwise, the original URL.
 */
String buildGitUrl(String gitUrl) {
    def knownLegacyJenkinsUrls = [
            'https://ci.system.local/'
    ]
    boolean isLegacyJenkins = knownLegacyJenkinsUrls.any {env.JENKINS_URL?.startsWith(it) }

    if (isLegacyJenkins) {
        echo "Build läuft auf einem Legacy-Jenkins-Server unter ci.system.local/[TEAM]. Git-URL wird um Credentials erweitert."
        return gitUrl.replaceFirst('https://', "https://${URLEncoder.encode(env.GIT_USERNAME, 'UTF-8')}:${URLEncoder.encode(env.GIT_PASSWORD, 'UTF-8')}@")
    } else {
        echo "Build läuft auf dem SDA-Jenkins-Server (sda-jenkins.system.local). Git-URL wird nicht verändert."
        return gitUrl
    }
}
