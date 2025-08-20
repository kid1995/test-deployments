import com.cloudbees.groovy.cps.NonCPS
import de.signaliduna.TargetSegment
import groovy.json.JsonSlurperClassic

@groovy.transform.Field
def OC_CLI_REQUEST_TIMEOUT = "10m"

/**
 * Compose the service namespace by the given parameters.
 */
String getServiceNamespace(String serviceGroup, String serviceName, TargetSegment targetSegment) {
    return "$serviceGroup-$serviceName-$targetSegment"
}

/**
 * Login to openshift and change to the project service namespace, which is determined by the given parameters.
 */
void login(String serviceGroup, String serviceName, TargetSegment targetSegment) {
    withCredentials([string(credentialsId: "${serviceGroup}-api-${targetSegment}", variable: 'TOKEN')]) {
        sh "oc login ${getOpenshiftUrl(targetSegment)} --token $TOKEN --insecure-skip-tls-verify --config ${getCliContext()}"
        sh "oc project ${getServiceNamespace(serviceGroup, serviceName, targetSegment)} --config ${getCliContext()}"
    }
}

/**
 * Process the template by the given template parameters.
 */
void processTemplate(String propertiesPath, Map<String, String> templateParameters) {
    if (!templateParameters.containsKey('BASE_ROUTE_URL') &&
         templateParameters.containsKey('PROJECT_URL') &&
         templateParameters.containsKey('RESOURCE_NAME')) {
        templateParameters['BASE_ROUTE_URL'] = "${templateParameters['RESOURCE_NAME']}-${templateParameters['PROJECT_URL']}"
    }

    if (templateParameters.containsKey('BASE_ROUTE_URL')) {
        def baseUrl = templateParameters['BASE_ROUTE_URL'].replaceAll('\\..+$', '')
        if (baseUrl.length() > 60) { // 60 chars to allow prefix "adm-…" for admin routes
            echo "┳┻|\n┻┳|\n┳┻| _     HEADS UP! The parameter BASE_ROUTE_URL is too long (>60 characters!) and will most likely break your deployment!\n┻┳| •.•)  Current BASE_ROUTE_URL = \"${templateParameters['BASE_ROUTE_URL']}\"\n┳┻|⊂ﾉ     Define a shorter BASE_ROUTE_URL via argument additionalParams in deployApplication().\n Additional information: To achieve a reduction below 60 char APP_NAME + SERVICE_GROUP + SERVICE_NAME should be less than 32 than characters.\n(BASE_ROUTE_URL is used to provide Ingress HTTP Access to Services)\n┻┳|\n┳┻|"
        }
    }

    sh """
        set +x # don't divulge secrets by accident
        echo "--> oc process -f $propertiesPath | oc apply"
        oc process -f $propertiesPath \
            ${createTemplateParameterString(templateParameters)} \
            --ignore-unknown-parameters=true \
            --config ${getCliContext()} | oc apply --config ${getCliContext()} -f -
    """
}

/**
 * Deploy the container to openshift inside a namespace, which is determined by the given parameters.
 */
void deployContainer(String serviceGroup, String serviceName, TargetSegment targetSegment, String resourceName, Integer rolloutStatusDelaySeconds = 0) {
    try {

        sh """
            oc rollout latest dc/${resourceName} -n ${getServiceNamespace(serviceGroup, serviceName, targetSegment)} --config ${getCliContext()} || true
        """

        if(rolloutStatusDelaySeconds > 0){
            println "Additional rolloutStatusDelaySeconds  provided: " + rolloutStatusDelaySeconds.toString() + ". Waiting."
            sleep(rolloutStatusDelaySeconds)
        }

        sh """
            oc rollout status dc/${resourceName} --request-timeout=${OC_CLI_REQUEST_TIMEOUT} -n ${getServiceNamespace(serviceGroup, serviceName, targetSegment)} --config ${getCliContext()}
        """

    } catch (Exception e) {
        println "Deployment of $resourceName failed. Printing last 100 lines of the log from failed POD:"
        def podsInBranch = getPodsForCurrentBranch()
                .items
                .findAll {it.metadata.annotations['openshift.io/deployment-config.name'] == resourceName}
        def lastPod = getLastPod(podsInBranch)
        sh "oc logs ${lastPod.metadata.name} --config ${getCliContext()} --all-containers --tail=100"
        throw e
    }
}

@NonCPS
private def getLastPod(pods) {
    return pods.max {a, b -> a.metadata.creationTimestamp.compareTo(b.metadata.creationTimestamp)}
}

private def getPodsForCurrentBranch() {
    def filteredBranchName = filterBranchName(si_git.branchName())
    def podsJson = sh(
            returnStdout: true,
            script: "oc get pods --selector='branch=${filteredBranchName}' -o json --config ${getCliContext()}"
    )
    return new JsonSlurperClassic().parseText(podsJson)
}

/**
 * Checks the rollout of a statefulset
 */
void checkStatefulsetRollout(String serviceGroup, String serviceName, TargetSegment targetSegment, String resourceName) {
    sh """
        oc rollout status sts/${resourceName} --request-timeout=${OC_CLI_REQUEST_TIMEOUT} -n ${getServiceNamespace(serviceGroup, serviceName, targetSegment)} --config ${getCliContext()}
    """
}

/**
 * Deploy the Application to openshift inside a namespace, which is determined by the given parameters.
 * This operation does the whole workflow by using login, processTemplate and deploy container.
 * Furthermore, obsolete assets are removed.
 */
void deployApplication(String serviceGroup, String serviceName, String applicationName, TargetSegment targetSegment, Map additionalParams = [:], deleteObsoleteBranches = true) {

    def rolloutStatusDelaySeconds = 0

    try {
        if(additionalParams.containsKey("ROLLOUT_STATUS_DELAY_SECONDS")) {
            rolloutStatusDelaySeconds = Integer.parseInt(additionalParams["ROLLOUT_STATUS_DELAY_SECONDS"])
        }
    }catch (Exception e) {
        println "ERROR: additionalParam of ROLLOUT_STATUS_DELAY_SECONDS has beeen provided. Parse error for ROLLOUT_STATUS_DELAY_SECONDS occurred. Using default: 0."
    }

    if (targetSegment == TargetSegment.prd) {
        si_its360.errorForDeploymentWithoutConfiguredCiId(serviceGroup, serviceName)
    }

    // This step is necessary cause deployment has to be done from internal openshift registry and the images are only pushed to external registry during build
    if (targetSegment == TargetSegment.abn) {
        si_docker.publishImageFromExternalToInternalRegistry(serviceGroup, serviceName, applicationName)
    }

    processApplicationTemplate(serviceGroup, serviceName, applicationName, targetSegment, additionalParams, deleteObsoleteBranches)

    def applicationResourceName = getApplicationResourceName(applicationName, targetSegment)

    // Check if same 'applicationResourceName' exists for dc && sts
    if (si_openshift_thirdparty.isDeploymentConfig(serviceGroup, serviceName, targetSegment, applicationResourceName) &&
            si_openshift_thirdparty.isStatefulSet(serviceGroup, serviceName, targetSegment, applicationResourceName)) {
        throw new Exception("Expected 1 resource with name ${applicationResourceName} but found 2 resources (Statefulset and DeploymentConfig). To solve this issue create a new feature branch.")
    }

    if(si_openshift_thirdparty.isDeploymentConfig(serviceGroup, serviceName, targetSegment, applicationResourceName)) {
        deployContainer(serviceGroup, serviceName, targetSegment, applicationResourceName, rolloutStatusDelaySeconds)
    } else if(si_openshift_thirdparty.isStatefulSet(serviceGroup, serviceName, targetSegment, applicationResourceName)) {
        checkStatefulsetRollout(serviceGroup, serviceName, targetSegment, applicationResourceName)
    } else {
        println "no status viewer has been implemented for " + applicationResourceName + ", rollout not possible !"
    }
}

/**
 * Deploy the Application to openshift inside a namespace, which is determined by the given parameters.
 * This operation does the whole workflow by using login, processTemplate and deploy container.
 * Furthermore, obsolete assets are removed.
 */
void deployApplication(String imagePath, String serviceGroup, String serviceName, String applicationName, TargetSegment targetSegment, Map additionalParams = [:], deleteObsoleteBranches = true) {
    def rolloutStatusDelaySeconds = 0

    try {
        if(additionalParams.containsKey("ROLLOUT_STATUS_DELAY_SECONDS")) {
            rolloutStatusDelaySeconds = Integer.parseInt(additionalParams["ROLLOUT_STATUS_DELAY_SECONDS"])
        }
    }catch (Exception e) {
        println "ERROR: additionalParam of ROLLOUT_STATUS_DELAY_SECONDS has beeen provided. Parse error for ROLLOUT_STATUS_DELAY_SECONDS occurred. Using default: 0."
    }

    if (targetSegment == TargetSegment.prd) {
        si_its360.errorForDeploymentWithoutConfiguredCiId(serviceGroup, serviceName)
    }

    Map image = si_docker.validateImagePath(imagePath, targetSegment)
    String imagePathToDeploy = imagePath

    // This step is necessary cause deployment has to be done from internal openshift registry and the images are only pushed to external registry during build
    if (targetSegment == TargetSegment.abn || targetSegment == TargetSegment.prd) {
        imagePathToDeploy = si_docker.publishImageFromExternalToInternalRegistry(image, serviceGroup, serviceName, targetSegment)
    }

    processApplicationTemplate(imagePathToDeploy, serviceGroup, serviceName, applicationName, targetSegment, additionalParams, deleteObsoleteBranches)

    def applicationResourceName = getApplicationResourceName(applicationName, targetSegment)

    if(si_openshift_thirdparty.isDeploymentConfig(serviceGroup, serviceName, targetSegment, applicationResourceName)) {
        deployContainer(serviceGroup, serviceName, targetSegment, applicationResourceName, rolloutStatusDelaySeconds)
    } else if(si_openshift_thirdparty.isStatefulSet(serviceGroup, serviceName, targetSegment, applicationResourceName)) {
        checkStatefulsetRollout(serviceGroup, serviceName, targetSegment, applicationResourceName)
    } else {
        println "no status viewer has been implemented for " + applicationResourceName + ", rollout not possible !"
    }
}

void processApplicationTemplate(String serviceGroup, String serviceName, String applicationName, TargetSegment targetSegment, Map additionalParams = [:], deleteObsoleteBranches = true) {
    def imagePath = si_docker.getInternalImagePath(serviceGroup, serviceName, applicationName, targetSegment)
    processApplicationTemplate(imagePath, serviceGroup, serviceName, applicationName, targetSegment, additionalParams, deleteObsoleteBranches)
}

void processApplicationTemplate(String imagePath, String serviceGroup, String serviceName, String applicationName, TargetSegment targetSegment, Map additionalParams = [:], deleteObsoleteBranches = true) {
    def projectUrl = getProjectUrl(serviceGroup, serviceName, targetSegment)

    login(serviceGroup, serviceName, targetSegment)

    if (targetSegment == targetSegment.tst && deleteObsoleteBranches) {
        removeObsoleteAssets(serviceGroup, serviceName)
    }

    def templatePath = "openshift/${applicationName}-template.yml"
    def configMapResourceName = processStageConfigMap(targetSegment)
    def applicationResourceName = getApplicationResourceName(applicationName, targetSegment)
    def templateParameters = [
            'IMAGE_NAME'        : imagePath,
            'BRANCH_NAME'       : filterBranchName(si_git.branchName()),
            'COMMIT_ID'         : si_git.shortCommitId(),
            'RESOURCE_NAME'     : applicationResourceName,
            'PROJECT_URL'       : projectUrl,
            'TARGET_SEGMENT'    : targetSegment,
            'STAGE_PROPERTY_MAP': configMapResourceName
    ] << additionalParams
    processTemplate(templatePath, templateParameters)
}

String processStageConfigMap(TargetSegment targetSegment) {
    def propertiesPath = "openshift/${targetSegment}.properties.yml"
    def configMapResourceName = getConfigMapResourceName(targetSegment)
    configMapParams = [
        'BRANCH_NAME'  : filterBranchName(si_git.branchName()),
        'RESOURCE_NAME': configMapResourceName
    ]
    processTemplate(propertiesPath, configMapParams)
    return configMapResourceName
}

/**
 * Returns the openshift session token.
 */
String currentSessionToken() {
    return sh(
            returnStdout: true,
            script: "oc whoami -t --config ${getCliContext()}"
    ).trim()
}

/**
 * Returns the resource name of the config-map for the given target segment.
 */
String getConfigMapResourceName(TargetSegment targetSegment) {
    def branchName = filterBranchName(si_git.branchName())
    def resourceNamePrefix = getResourceNamePrefix(branchName, targetSegment)
    return "$resourceNamePrefix-properties"
}

/**
 * Returns the application resource name by the given application name und target segment.
 */
String getApplicationResourceName(String applicationName, TargetSegment targetSegment) {
    def branchName = filterBranchName(si_git.branchName())
    def resourceNamePrefix = getResourceNamePrefix(branchName, targetSegment)
    return "$resourceNamePrefix-$applicationName"
}

/**
 * Return the project url by the given parameters.
 */
String getProjectUrl(String serviceGroup, String serviceName, TargetSegment targetSegment) {
    def namespace = getServiceNamespace(serviceGroup, serviceName, targetSegment)
    return "${namespace}.${getClusterBaseUrl(targetSegment)}"
}

/**
 * Return the resources for one label
 */
String getResourcesByLabel(String resource, String key, String value) {
    String resourceNames = sh (
        returnStdout: true,
        script: "oc get ${resource} -l \"${key}\"=\"${value}\" --config ${getCliContext()}"
    ).trim()
    return resourceNames
}

/**
 * Return information for pods that match one pattern
 */
String getResourcesByPattern(String resourceType, String pattern) {
    String resourceInfo = sh (
        returnStdout: true,
        script: "oc get ${resourceType} --config ${getCliContext()} | grep \"${pattern}\" || true"
    ).trim()
    return resourceInfo
}

String getResourceInformation(String resourceType, String resourceName, String templateSelector) {
    String resourceInfo = sh (
        returnStdout: true,
        script: "oc get --export ${resourceType} ${resourceName} -o template --template '${templateSelector}' --config ${getCliContext()}"
    ).trim()
    return resourceInfo
}

String getPodLogs(String podName) {
    String resourceLogs = sh(
        returnStdout: true,
        script: "oc logs ${podName} --config ${getCliContext()} --all-containers"
    ).trim()
    return resourceLogs
}

boolean patchResource(String resourceType, String resourceName, String templateSelector) {
    int returnCode = sh (
        returnStatus: true,
        script: "oc patch ${resourceType} ${resourceName} -p ${templateSelector} --config ${getCliContext()}"
    )
    return returnCode == 0
}

String getAllPods() {
    String pods = sh(
        returnStdout: true,
        script: "oc get pods --config ${getCliContext()}"
    ).trim()
    return pods
}

/**
 * Return if resource exists
 */
boolean resourceExists(String resourceType, String resourceName) {
    int returnCode = sh (
        returnStatus: true,
        script: "oc get ${resourceType} ${resourceName} --config ${getCliContext()}"
    )
    return returnCode == 0
}

/**
 * Return confirmation that the deletion of a resource (by label) was successful
 */
boolean deleteResourcesByLabel(String resourceType, String key, String value) {
    int returnCode = sh (
        returnStatus: true,
        script: "oc delete ${resourceType} -l \"${key}\"=\"${value}\" --config ${getCliContext()}"
    )
    return returnCode == 0
}

/**
 * Return confirmation that the deletion of a resource (by name) was successful
 */
boolean deleteResourceByName(String resourceType, String resourceName) {
    int returnCode = sh (
        returnStatus: true,
        script: "oc delete ${resourceType} ${resourceName} --config ${getCliContext()}"
    )
    return returnCode == 0
}

/**
 * Return confirmation that the downscaling of a deploymentConfig (by name) was successful
 * @throws: IllegalArgumentException for Production-Use.
 */
boolean scaleDeploymentConfigDown(String serviceGroup, String serviceName, TargetSegment targetSegment, String resourceName) {
    if (TargetSegment.prd.equals(targetSegment)) {
        throw new IllegalArgumentException("Target segment $targetSegment is not allowed for downscaling!")
    }
    login(serviceGroup, serviceName, targetSegment)
    int returnCode = sh (
        returnStatus: true,
        script: "oc scale deploymentConfig ${resourceName} --replicas=0 --config ${getCliContext()}"
    )
    return returnCode == 0
}

/**
 * Scales down the statefulset of the current branch.
 */
void scaleStatefulsetDown(String serviceGroup, String serviceName, TargetSegment targetSegment, String resourceName) {
    login(serviceGroup, serviceName, targetSegment)
    sh (
        "oc scale statefulset ${resourceName} --replicas=0 --config ${getCliContext()}"
    )
}

boolean createJobFromCronjob(String jobName, String cronjobName) {
    int returnCode = sh (
        returnStatus: true,
        script: "oc create job ${jobName} --from cronjob/${cronjobName} --config ${getCliContext()}"
    )
    return returnCode == 0
}

void cleanUpDeployedRenovateBranch(String serviceGroup, String serviceName, TargetSegment targetSegment) {
    if (targetSegment == TargetSegment.prd || targetSegment == TargetSegment.abn) {
        throw new Exception("TargetSegment must be tst!")
    }
    def branch = si_git.branchName();
    echo "Shall delete renovate deployment: $branch"
    def filteredBranch = filterBranchName(branch)
    def isRenovateBranch = branch.startsWith("renovate")
    if (isRenovateBranch) {
        login(serviceGroup, serviceName, targetSegment)
        sh "oc delete configmaps,cronjobs,deployments,deploymentconfigs,horizontalpodautoscalers,jobs,persistentvolumeclaims,routes,services,statefulsets --selector 'branch=$filteredBranch' --config cli-context"
    }
}

private String getResourceNamePrefix(String originBranchName, TargetSegment targetSegment) {
    if (TargetSegment.tst.equals(targetSegment)) {
        return originBranchName
    } else {
        return targetSegment.toString()
    }
}

private static String getClusterBaseUrl(TargetSegment targetSegment) {
    if (TargetSegment.tst.equals(targetSegment)) {
        return "osot.system.local"
    } else if (TargetSegment.abn.equals(targetSegment)) {
        return "cloud-test.system.local"
    } else {
        return "cloud.system.local"
    }
}

private static String createTemplateParameterString(Map<String, String> templateParameters) {
    // StringBuilder doesn't seem to work in Jenkins
    String paramString = "";

    for (String key : templateParameters.keySet()) {
        paramString += "-p " + key + "=" + templateParameters.get(key) + " "
    }
    return paramString;
}

String getOpenshiftUrl(TargetSegment targetSegment) {
    switch (targetSegment) {
        case TargetSegment.tst:
            return "https://openshift.osot.system.local:8443"

        case TargetSegment.abn:
        case TargetSegment.prd:
            return "https://openshift-console-prod.system.local:8443"

        default:
            throw new IllegalArgumentException("Unknown OpenShift URL for target segment $targetSegment")
    }
}

private static String getOpenshiftAssetTypes() {
    return "configmaps,cronjobs,deployments,deploymentconfigs,horizontalpodautoscalers,jobs,pdb,postgresqls,persistentvolumeclaims,routes,secrets,services,statefulsets"
}

private static String getCliContext() {
    return "cli-context"
}

// #####################################
// ### Remove obsolete assets in TST ###
// #####################################

/**
 * Removes all OpenShift assets which do not have a corresponding git branches.
 */
void removeObsoleteAssets(String serviceGroup, String serviceName) {
    login(serviceGroup, serviceName, TargetSegment.tst)

    // get git branches
    def gitBranches = listGitBranches()
    println("active git branches: ${gitBranches}")

    // get OpenShift assets
    def osAssets = collectOsAssets()
    def branchesAsOsNames = generateOpenShiftBranchNames(gitBranches)
    println("existing OpenShift branches: ${osAssets}")

    // compare branches with assets
    def obsoleteBranchList = new ArrayList()
    for (assets in osAssets) {
        if (!branchesAsOsNames.contains(assets)) {
            obsoleteBranchList.add(assets)
        }
    }

    // delete unused OpenShift branches
    if (obsoleteBranchList.isEmpty()) {
        println('No obsolete branches found.')
    } else {
        println("Deleting obsolete branches: ${obsoleteBranchList}")
        deleteOsAssets(obsoleteBranchList)
    }
}

protected static String filterBranchName(String branchName) {
    // max 64 chars in total,
    // we only use 20 chars for the branch part: RATTLE-123
    // only a-z0-9- are allowed
    // no trailing '-'

    def maxLengthOfBranchName = 20

    def convertedName = branchName
            .toLowerCase()
            .replaceAll('^feature/', '')    // shorten 'feature/...' -> '...'
            .replaceAll('^bugfix/', '')     // shorten 'bugfix/...' -> '...'
            .replaceAll('^hotfix/', '')     // shorten 'hotfix/...' -> '...'
            .replaceAll('^example/', '')    // shorten 'example/...' -> '...'
            .replaceAll('^renovate/', '')   // shorten 'renovate/...' -> '...'
            .replaceAll('[^a-z0-9-]', '-')  // replace special characters

    def limit = convertedName.length() > maxLengthOfBranchName ? maxLengthOfBranchName : convertedName.length()

    return convertedName
            .substring(0, limit)
            .replaceAll('-$', "")           // cut '-' at the end
}

/**
 * Converts a list of branch names into OpenShift compatible branch names
 */
private static ArrayList<String> generateOpenShiftBranchNames(ArrayList<String> branchNames) {
    def openShiftBranchNames = new ArrayList()

    for (branchName in branchNames) {
        openShiftBranchNames.add(filterBranchName(branchName))
    }

    return openShiftBranchNames
}

/**
 * Collect the list of remote git branches, cutting remote/origin/ prefix from their names
 */
private ArrayList<String> listGitBranches() {
    def branchList = new ArrayList()
    def branchListString = sh(returnStdout: true,
            script: """
                git remote update origin --prune
                git branch -r
            """).trim()

    if (branchListString.length() > 0) {
        for (branchName in branchListString.readLines()) {
            if (branchName.contains('origin/')) {
                branchList.add(branchName.replaceAll('^ *origin/', ''))
            }
        }
    }

    return branchList
}

/**
 * delete all assets with label application and branch
 */
private void deleteOsAssets(ArrayList branchList) {
    for (branch in branchList) {
        sh "oc delete ${getOpenshiftAssetTypes()} --selector branch=${branch} --config ${getCliContext()}"
    }
}

/**
 * return a (unique) list of open shift assets available, given the configuration within the configFileName
 */
private ArrayList collectOsAssets() {
    return sh(
            returnStdout: true,
            script: """
                oc get --no-headers=true \
                    --output=custom-columns=BRANCH:.metadata.labels.branch \
                    --config ${getCliContext()} \
                    ${getOpenshiftAssetTypes()} \
                    | grep --invert '<none>' | sort | uniq
            """)
            .trim()
            .readLines()
}
