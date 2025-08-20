import de.signaliduna.TargetSegment
import hudson.FilePath

/**
 * default behavior of services, name includes branchname as praefix.
 */
@groovy.transform.Field
def withBranchNameAsPraefix = true

/**
 * This method will deactive using branchname as praefix in service name.
 */
public void disableFeatureBranchSupport() {
    withBranchNameAsPraefix = false
}

/**
 * Deploys a application on given stage
 */
public void deployApplication(String serviceGroup, String serviceName, String applicationName, Map imageList, String providerRegistry, TargetSegment targetSegment, Map additionalParams = [:]) {
    if(TargetSegment.prd.equals(targetSegment)) {
        si_docker.publishImagesAbnToPrd(serviceGroup, serviceName, imageList[applicationName])
    } else {
        si_docker.importImagesFromProviderRegistry(serviceGroup, serviceName, imageList[applicationName], providerRegistry, targetSegment)
    }
    deployApplicationByProcessingTemplate(serviceGroup, serviceName, applicationName, imageList, providerRegistry, targetSegment, additionalParams)    
}

/**
 * Deploy the Application to openshift inside a namespace, which is determined by the given parameters.
 * This operation does the whole workflow by using login, processTemplate and deploy container.
 * Furthermore, obsolete assets are removed.
 */
private void deployApplicationByProcessingTemplate(String serviceGroup, String serviceName, String applicationName, Map imageList, String providerRegistry, TargetSegment targetSegment, Map additionalParams = [:]) {
    def rolloutStatusDelaySeconds = 0

    try {
        if(additionalParams.containsKey("ROLLOUT_STATUS_DELAY_SECONDS")) {
            rolloutStatusDelaySeconds = Integer.parseInt(additionalParams["ROLLOUT_STATUS_DELAY_SECONDS"])
        }
    }catch (Exception e) {
        println "ERROR: additionalParam of ROLLOUT_STATUS_DELAY_SECONDS has beeen provided. Parse error for ROLLOUT_STATUS_DELAY_SECONDS occurred. Using default: 0."
    }

    def projectUrl = si_openshift.getProjectUrl(serviceGroup, serviceName, targetSegment)
    def propertiesPath = getPropertiesPath(applicationName, targetSegment)
    def templatePath = "openshift/${applicationName}/template.yml"
    
    def branchName = si_openshift.filterBranchName(si_git.branchName())
    def configMapResourceName = ""

    si_openshift.login(serviceGroup, serviceName, targetSegment)

  	if (targetSegment == targetSegment.tst) {
        si_openshift.removeObsoleteAssets(serviceGroup, serviceName)
    }
    // properties for service exists
    // configure and deploy configMap
    if(!propertiesPath.equals("")) {
        configMapResourceName = getConfigMapResourceName(applicationName, targetSegment)
        configMapParams = [
            'BRANCH_NAME'  : branchName,
            'RESOURCE_NAME': configMapResourceName
        ]
        si_openshift.processTemplate(propertiesPath, configMapParams)
    }

    // for deployments with more than 1 image included
    if (imageList[applicationName].size > 1) {
        def index = 0
        for(def image in imageList[applicationName]) {
            ensureImageIsAvailableForDeployment(serviceGroup, serviceName, image.imageName, image.tagName, targetSegment)
            def imagePath = si_docker.getImagePath(serviceGroup, serviceName, image.imageName, image.tagName, targetSegment)
            additionalParams.put('IMAGE_NAME_' + index++, imagePath)
        }
    } else{
        ensureImageIsAvailableForDeployment(serviceGroup, serviceName, imageList[applicationName][0].imageName, imageList[applicationName][0].tagName, targetSegment)
        def imagePath = si_docker.getImagePath(serviceGroup, serviceName, imageList[applicationName][0].imageName, imageList[applicationName][0].tagName, targetSegment)
       additionalParams.put('IMAGE_NAME', imagePath)
    }

    def applicationResourceName = getApplicationResourceName(applicationName, targetSegment)

    def templateParameters = [
            'BRANCH_NAME'       : branchName,
            'COMMIT_ID'         : si_git.shortCommitId(),
            'RESOURCE_NAME'     : applicationResourceName,
            'PROJECT_URL'       : projectUrl,
            'TARGET_SEGMENT'    : targetSegment,
            'STAGE_PROPERTY_MAP': configMapResourceName
    ] << additionalParams

    if(!propertiesPath.equals("")) {
       templateParameters.put('STAGE_PROPERTY_MAP', configMapResourceName)
    }

    si_openshift.processTemplate(templatePath, templateParameters)
    if(isDeploymentConfig(serviceGroup, serviceName, targetSegment, applicationResourceName)) {
        si_openshift.deployContainer(serviceGroup, serviceName, targetSegment, applicationResourceName,rolloutStatusDelaySeconds)
    } else if(isStatefulSet(serviceGroup, serviceName, targetSegment, applicationResourceName)) {
        si_openshift.checkStatefulsetRollout(serviceGroup, serviceName, targetSegment, applicationResourceName)
    } else{
        println "no status viewer has been implemented for " + applicationResourceName + ", rollout not possible !"
    } 
}

/**
 *  This step is necessary cause ABN/PRD deployment has to be done from internal openshift registry and the imported images are only pushed to dev.docker.system.local (tst) or prod.docker.system.local (abn)
 *  for compliance reasons.
 *  In case of ABN, the images have to be imported from prod.docker.system.local to openshift internal registry
 */
private void ensureImageIsAvailableForDeployment(String serviceGroup, String serviceName, String imageName, String imageTag, TargetSegment targetSegment){
    if (targetSegment == TargetSegment.abn){
        si_docker.publishImageFromExternalToInternalRegistry(serviceGroup, serviceName, imageName, imageTag)
    }
}

/**
 * Returns true if given ressource is a deploymentconfig
 */
boolean isDeploymentConfig(String serviceGroup, String serviceName, TargetSegment targetSegment, String applicationResourceName) {
    int returnCode = sh (
        returnStatus: true,
        script: "oc get dc -n ${serviceGroup}-${serviceName}-${targetSegment.toString()} -o name --config cli-context | grep ${applicationResourceName}"
    )
    return returnCode == 0
}

/**
 * Returns true if given ressource is a statefulset
 */
boolean isStatefulSet(String serviceGroup, String serviceName, TargetSegment targetSegment, String applicationResourceName) {
    int returnCode = sh (
        returnStatus: true,
        script: "oc get sts -n ${serviceGroup}-${serviceName}-${targetSegment.toString()} -o name --config cli-context | grep ${applicationResourceName}"
    )
    return returnCode == 0
}

/** 
 * Returns a property file path for configmaps. The returned value depends on style of property file.
 * Empty String will be returned, if no property exists with given criteria.
  */
String getPropertiesPath(String applicationName, TargetSegment targetSegment) {

    def channel = Jenkins.getInstance().getComputer(env['NODE_NAME']).getChannel()
    def propertiesPath = pwd() + "/openshift/${applicationName}/${targetSegment.toString()}.properties.yml"

    // check preferred style (service/stage.properties.yml)
    if (!(new FilePath(channel, propertiesPath).exists())) {
        // check old style (stage.properties.yml)
        propertiesPath = pwd() + "/openshift/${targetSegment.toString()}.properties.yml"
        if (!(new FilePath(channel, propertiesPath).exists())) {
            propertiesPath = ""
            println "No Property found"        
        } else {
            println "Deprecated: style of propertyfile actual: ${targetSegment.toString()}.properties.yml " +  "expected: ${applicationName}/${targetSegment.toString()}.properties.yml"
        }
    } 

    return propertiesPath
}

/**
 * Returns the application resource name by the given application name und target segment.
 */
String getApplicationResourceName(String applicationName, TargetSegment targetSegment) {
    if(!withBranchNameAsPraefix) {
        return "$applicationName"
    }
    return si_openshift.getApplicationResourceName(applicationName, targetSegment)  
}

/**
 * Returns the resource name of the config-map for the given target segment.
 */
String getConfigMapResourceName(String applicationName, TargetSegment targetSegment) {
    def branchName = si_openshift.filterBranchName(si_git.branchName())
    def resourceNamePrefix = si_openshift.getResourceNamePrefix(branchName, targetSegment)
    if(!withBranchNameAsPraefix) {
        return "$applicationName-properties"
    }
    return "$resourceNamePrefix-$applicationName-properties"
}
