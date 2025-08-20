import com.cloudbees.groovy.cps.NonCPS
import de.signaliduna.TargetSegment
import groovy.transform.Field

@Field
private static final List DOCKER_HOSTS = ["app416100.system.local:2376", 
                                          "app416101.system.local:2376",
                                          "app416102.system.local:2376",
                                          "app416103.system.local:2376"]
@Field
private static final String DEV_DOCKER_REGISTRY = "dev.docker.system.local"
@Field
private static final String PROD_DOCKER_REGISTRY = "prod.docker.system.local"
@Field
private static final String INT_NAME_OPENSHIFT_DOCKER_REGISTRY = "docker-registry.default.svc:5000"
@Field
private static final String EXT_NAME_OPENSHIFT_DOCKER_REGISTRY = "docker-registry-default.cloud.system.local"
@Field
def DOCKER_TLSVERIFY = "--tlsverify"
@Field
def DOCKER_TLSCACERT = "--tlscacert /tls/docker-buildfarm-ca.pem"
@Field
def DOCKER_TLSCERT = "--tlscert /tls/docker-buildfarm-jenkins-client-cert.pem"
@Field
def DOCKER_TLSKEY = "--tlskey /tls/key.pem"
@Field
def DOCKER_TLS_PARAMETER = getDockerTlsParameter(getDockerHost())
@Field
def DOCKER_BUILD_VERBOSE = "false"

@Field
private static final List TAG_FORMATS = [
    new SimpleVersionComparator('simple number tag', /^(\d+)$/), // e.g. '4711'
    new SimpleVersionComparator('simple numbered postfix tag', /^[A-Za-z].*-(\d+)$/), // e.g. 'alpine-4711'
    new SimpleVersionComparator('simple number prefix tag', /^(\d+)-[A-Za-z].*$/), // e.g. '4711-alpine'
    new SemanticVersionComparator('semantic version tag', /^(\d+)\.(\d+)\.(\d+)$/), // e.g. '1.2.3'
    new SemanticVersionComparator('semantic version postfix tag', /^[A-Za-z].*-(\d+)\.(\d+)\.(\d+)$/), // e.g. 'alpine-1.2.3'
    new SemanticVersionComparator('semantic version prefix tag', /^(\d+)\.(\d+)\.(\d+)-[A-Za-z].*$/) // e.g. '1.2.3-alpine'
]

/**
 * Returns the image path for the internal docker registry by given image name and tag.
 */
protected String getInternalImagePathByNameAndTag(String serviceGroup, String serviceName, TargetSegment targetSegment, String imageName, String imageTag) {
    String dockerRegistry = getInternalDockerRegistry(targetSegment)
    return getImagePathForRegistryByNameAndTag(dockerRegistry, serviceGroup, serviceName, targetSegment, imageName, imageTag)
}

/**
 * Returns the image path for the external docker registry by given image name and tag.
 */
protected String getExternalImagePathByNameAndTag(String serviceGroup, String serviceName, TargetSegment targetSegment, String imageName, String imageTag) {
    String dockerRegistry = getExternalDockerRegistry(targetSegment)
    return getImagePathForRegistryByNameAndTag(dockerRegistry, serviceGroup, serviceName, targetSegment, imageName, imageTag)
}

/**
 * Returns the image path for the internal docker registry.
 */
protected String getInternalImagePath(String serviceGroup, String serviceName, String applicationName, TargetSegment targetSegment) {
    String dockerRegistry = getInternalDockerRegistry(targetSegment)
    return getImagePathForRegistryByNameAndTag(dockerRegistry, serviceGroup, serviceName, targetSegment, applicationName, generateImageTag())
}

public String getExternalImagePath(String serviceGroup, String serviceName, String applicationName, TargetSegment targetSegment) {
    String dockerRegistry = getExternalDockerRegistry(targetSegment)
    return getImagePathForRegistryByNameAndTag(dockerRegistry, serviceGroup, serviceName, targetSegment, applicationName, generateImageTag())
}

/**
 * Returns the image path for the given docker registry.
 */
private String getImagePathForRegistryByNameAndTag(String dockerRegistry, String serviceGroup, String serviceName, TargetSegment targetSegment, String imageName, String imageTag) {
    def serviceNamespace = si_openshift.getServiceNamespace(serviceGroup, serviceName, targetSegment)
    return "$dockerRegistry/$serviceNamespace/$imageName:$imageTag"
}

/**
 * Build docker container image and push to registry with a specific dockerfile.
 */
public String buildImageWithDockerfile(String context, String dockerfile, String serviceGroup, String serviceName, String applicationName, TargetSegment targetSegment, Map<String, String> buildArgs = [:]) {
    String dockerImagePath = getImagePathForPublishing(serviceGroup, serviceName, applicationName, targetSegment)
    buildAndPushImage(context, dockerfile, serviceGroup, serviceName, targetSegment, dockerImagePath, buildArgs)

    return dockerImagePath
}

public String buildImageWithDockerfileByNameAndTag(String context, String dockerfile, String serviceGroup, String serviceName, TargetSegment targetSegment, String imageName, String imageTag, Map<String,String> buildArgs = [:]) {
    String dockerImagePath = getImagePathForPublishingByNameAndTag(serviceGroup, serviceName, targetSegment, imageName, imageTag)
    buildAndPushImage(context, dockerfile, serviceGroup, serviceName, targetSegment, dockerImagePath, buildArgs)

    return dockerImagePath
}

/**
 * Adds build arguments to the docker build command
 * @param args Key-Value-Pairs with build parameters
 * @return the string that can be appended to the docker build command
 */
private String createBuildArgParams(Map<String, String> args) {
    if ( args ) {
        return args.entrySet().collect { entry ->
            "--build-arg '${entry.key}=${entry.value}'"
        }.join(" ")
    } else {
        return ""
    }
}

/**
 * Builds and pushes docker image with given dockerfile and image path
 */
private void buildAndPushImage(String context, String dockerfile, String serviceGroup, String serviceName, TargetSegment targetSegment, String dockerImagePath, Map<String, String> buildArgs = [:]){
    loginToSDADockerRegistries(serviceGroup)

    patchDockerfileToUseLatest(context, dockerfile, serviceGroup)

    String buildArgStr = createBuildArgParams(buildArgs)
    // Setting the DOCKER_BUILD_VERBOSE to true will show detailed output of your docker image build, which is very helpful
    // when searching errors. Value can be set and unset within the Jenkins Pipeline
    def buildInfoLevel = "-q"
    def errorPipe = ""
    if ( DOCKER_BUILD_VERBOSE == "true") {
        buildInfoLevel = "--progress=plain"
        errorPipe = " >> /dev/stdout 2>&1"
    }

    sh """
        docker --config ${serviceGroup} ${DOCKER_TLS_PARAMETER} build ${buildInfoLevel} -t ${dockerImagePath} ${buildArgStr} -f ${dockerfile} ${context}${errorPipe}
        docker --config ${serviceGroup} ${DOCKER_TLS_PARAMETER} push ${dockerImagePath}
    """
}

private void loginToExternalDockerRegistry(String serviceGroup, String serviceName, TargetSegment targetSegment) {
    loginToSDADockerRegistries(serviceGroup)
    switch (targetSegment) {
        case TargetSegment.tst:
            // No login needed for tst
            break;

        case TargetSegment.abn:
        case TargetSegment.prd:
            si_openshift.login(serviceGroup, serviceName, targetSegment)
            // user value will be ignored on integrated Docker registry
            // https://docs.openshift.com/container-platform/3.11/rest_api/index.html#rest-api-docker-login
            sh "set +x && docker --config ${serviceGroup} ${DOCKER_TLS_PARAMETER} login -u unused -p ${si_openshift.currentSessionToken()} ${getExternalDockerRegistry(targetSegment)}"
    }
}

private void loginToSDADockerRegistries(String serviceGroup){
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'anonymous-docker-registry', usernameVariable: 'ANONYMOUS_DOCKER_REGISTRY_USER', passwordVariable: 'ANONYMOUS_DOCKER_REGISTRY_PASSWORD']]) {
        sh """
            docker --config ${serviceGroup} ${DOCKER_TLS_PARAMETER} login -u ${ANONYMOUS_DOCKER_REGISTRY_USER} -p ${ANONYMOUS_DOCKER_REGISTRY_PASSWORD} ${DEV_DOCKER_REGISTRY}
        """
    }
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'base-image-pipeline', usernameVariable: 'PUSH_PROD_DOCKER_REGISTRY_USER', passwordVariable: 'PUSH_PROD_DOCKER_REGISTRY_PASSWORD']]) {
        sh """
           docker --config ${serviceGroup} ${DOCKER_TLS_PARAMETER} login -u ${PUSH_PROD_DOCKER_REGISTRY_USER} -p ${PUSH_PROD_DOCKER_REGISTRY_PASSWORD} ${PROD_DOCKER_REGISTRY}
        """
    }
}

private String getImagePathForPublishing(String serviceGroup, String serviceName, String applicationName, TargetSegment targetSegment){
    return getImagePathForPublishingByNameAndTag(serviceGroup, serviceName, targetSegment, applicationName, generateImageTag())
}

private String generateImageTag(){
    return "${si_git.shortCommitId()}.${env.BUILD_NUMBER}"
}

/**
 * Returns the image path for prod registry.
 */
private String getImagePathForPublishingByNameAndTag(String serviceGroup, String serviceName, TargetSegment targetSegment, String imageName, String imageTag){
    String dockerRegistry = getRegistryForPublishing(targetSegment)
    switch (targetSegment) {
        case TargetSegment.tst:
            String serviceNamespace = si_openshift.getServiceNamespace(serviceGroup, serviceName, targetSegment)
            return "$dockerRegistry/$serviceNamespace/$imageName:$imageTag"
        case TargetSegment.abn:
            String serviceNamespaceWithoutTargetSegment = "$serviceGroup-$serviceName"
            return "$dockerRegistry/$serviceNamespaceWithoutTargetSegment/$imageName:$imageTag"

        default:
            throw new IllegalArgumentException("Target segment $targetSegment is not supported for publishing images")
    }
}

private String getRegistryForPublishing(TargetSegment targetSegment){
    switch (targetSegment) {
        case TargetSegment.tst:
            return DEV_DOCKER_REGISTRY
        case TargetSegment.abn:
            return PROD_DOCKER_REGISTRY

        default:
            throw new IllegalArgumentException("Target segment $targetSegment is not supported for publishing images")
    }
}

/**
 * Build docker container image and push to registry.
 */
public String buildImage(String context, String serviceGroup, String serviceName, String applicationName, TargetSegment targetSegment, Map<String, String> buildArgs = [:]) {
    return buildImageWithDockerfile(context, "$context/Dockerfile", serviceGroup, serviceName, applicationName, targetSegment, buildArgs)
}

/**
 * Perform a tag on a imagePath which is build by the given parameters.
 */
public void publishImageByNameAndTag(String imagePath, String serviceGroup, String serviceName, TargetSegment targetSegment, String imageName, String imageTag) {
    String targetImagePath = getExternalImagePathByNameAndTag(serviceGroup, serviceName, targetSegment, imageName, imageTag)
    tagAndPushImage(serviceGroup, serviceName, targetSegment, imagePath, targetImagePath)
}

/**
 * Perform a tag on a imagePath which is build by the given parameters.
 */
public String publishImage(String imagePath, String serviceGroup, String serviceName, String applicationName, TargetSegment targetSegment) {
    String targetImagePath = getExternalImagePath(serviceGroup, serviceName, applicationName, targetSegment)
    tagAndPushImage(serviceGroup, serviceName, targetSegment, imagePath, targetImagePath)

    return targetImagePath.replace(EXT_NAME_OPENSHIFT_DOCKER_REGISTRY, INT_NAME_OPENSHIFT_DOCKER_REGISTRY)
}

private void tagAndPushImage(String serviceGroup, String serviceName, TargetSegment targetSegment, String sourceImagePath, String targetImagePath) {
    loginToExternalDockerRegistry(serviceGroup, serviceName, targetSegment)
    sh """
        docker --config ${serviceGroup} ${DOCKER_TLS_PARAMETER} pull ${sourceImagePath}
        docker --config ${serviceGroup} ${DOCKER_TLS_PARAMETER} tag ${sourceImagePath} ${targetImagePath}
        docker --config ${serviceGroup} ${DOCKER_TLS_PARAMETER} push ${targetImagePath}
    """
}

/**
 * Perform a retag on a imagePath which is build by the given parameters from abn to prd. 
 */
public void publishImageAbnToPrd(String serviceGroup, String serviceName, String applicationName) {
    String abnImagePath = getExternalImagePath(serviceGroup, serviceName, applicationName, TargetSegment.abn)
    publishImage(abnImagePath, serviceGroup, serviceName, applicationName, TargetSegment.prd)
}

private static String getInternalDockerRegistry(TargetSegment targetSegment) {
    switch (targetSegment) {
        case TargetSegment.tst:
            return DEV_DOCKER_REGISTRY

        case TargetSegment.abn:
        case TargetSegment.prd:
            return INT_NAME_OPENSHIFT_DOCKER_REGISTRY

        default:
            throw new IllegalArgumentException("Unknown docker registry for target segment $targetSegment")
    }
}

private static String getExternalDockerRegistry(TargetSegment targetSegment) {
    switch (targetSegment) {
        case TargetSegment.tst:
            return DEV_DOCKER_REGISTRY

        case TargetSegment.abn:
        case TargetSegment.prd:
            return EXT_NAME_OPENSHIFT_DOCKER_REGISTRY

        default:
            throw new IllegalArgumentException("Unknown docker registry for target segment $targetSegment")
    }
}

/**
 * Returns a random running docker-host from a list of pre-defined docker-hosts. 
 * If no running docker host were found an exception will be occured
 */
@NonCPS
private String getDockerHost() {
    def dockerHostList = DOCKER_HOSTS.collect()

    def random = new Random()
    String foundDockerHost = ""
    
    while ( foundDockerHost == "" && dockerHostList.size() > 0) {
        def i = random.nextInt(dockerHostList.size())
        
        if(!isDockerHostRunning(dockerHostList.get(i) as String)) {
            dockerHostList.remove(i)
        }
        else {
            foundDockerHost = dockerHostList[i]
        }
    }
    
    if (!foundDockerHost) {
        throw new Exception("No running docker host found !")
    }

    return foundDockerHost
}

/**
 *   Returns true if given docker host is running, otherwise false.
 */
@NonCPS
private boolean isDockerHostRunning(String dockerHost) {
    
    println "check if $dockerHost is running"

    boolean dockerHostRunning = false
    String tlsParameter = getDockerTlsParameter(dockerHost)

    def sout = new StringBuilder(), serr = new StringBuilder()
    def proc = "docker ${tlsParameter} version".execute()
    proc.consumeProcessOutput(sout, serr)
    proc.waitForOrKill(1000)


    if(serr) {
        println "$serr"
    } else {
        dockerHostRunning = true
    }

    return dockerHostRunning
}

/**
 * The NonCPS Annotation (see https://github.com/jenkinsci/workflow-cps-plugin/blob/master/README.md#technical-design) lets Groovy run
 * the so annotated functions in one go. Without this, the code will be serialized before and the method signature of pipeline code
 * will be changed (known limitation of pipeline groovy code) so that Jenkins throws a MissingMethodException which complaints of missing signature. Every method, that is called
 * from one NonCPS Method has annotated with NonCPS too.
 *
 *   Returns the tls parameter string for docker daemon remote access.
 */
@NonCPS
private String getDockerTlsParameter(String dockerHost) {
    if(!DOCKER_TLS_PARAMETER?.trim()){
        DOCKER_TLS_PARAMETER = "${DOCKER_TLSVERIFY} ${DOCKER_TLSCACERT} ${DOCKER_TLSCERT} ${DOCKER_TLSKEY} --host ${dockerHost}"
    }

    return DOCKER_TLS_PARAMETER
}

// === Backwards compatibility functions ===
// Keep the following functions for compatibility reasons

/**
 * @deprecated use #publishImageAbnToPrd instead
 */
public void tagContainerAbnToPrd(String serviceGroup, String serviceName, String applicationName) {
    si_jenkins.printDeprecationWarning("tagContainerAbnToPrd", "publishImageAbnToPrd")
    publishImageAbnToPrd(serviceGroup, serviceName, applicationName)
}
/**
 * @deprecated use #publishImage instead
 */
public void tagContainer(String imagePath, String serviceGroup, String serviceName, String applicationName, TargetSegment targetSegment) {
    si_jenkins.printDeprecationWarning("tagContainer", "publishImage")
    publishImage(imagePath, serviceGroup, serviceName, applicationName, targetSegment)
}

/**
 * @deprecated use #buildImageWithDockerfile instead
 */
public void buildContainerWithDockerfile(String context, String dockerfile, String serviceGroup, String serviceName, String applicationName, TargetSegment targetSegment) {
    si_jenkins.printDeprecationWarning("buildContainerWithDockerfile", "buildImageWithDockerfile")
    buildImageWithDockerfile(context, dockerfile, serviceGroup, serviceName, applicationName, targetSegment)
}

/**
 * @deprecated use #buildImage instead
 */
public void buildContainer(String context, String serviceGroup, String serviceName, String applicationName, TargetSegment targetSegment) {
    si_jenkins.printDeprecationWarning("buildContainer", "buildImage")
    buildImage(context, serviceGroup, serviceName, applicationName, targetSegment)
}

/**
 * @deprecated use #getInternalImagePath instead
 */
public String getImagePath(String serviceGroup, String serviceName, String applicationName, TargetSegment targetSegment) {
    si_jenkins.printDeprecationWarning("getImagePath", "getInternalImagePath")
    getInternalImagePath(serviceGroup, serviceName, applicationName, targetSegment)
}

// METHODS FOR THIRD PARTY DEPLOYMENT

/**
 * Handle list of images. Pull images from abn, tag it and afterward pushing to internal registry of prd.
 */
private void publishImagesAbnToPrd(String serviceGroup, String serviceName,  List<Map> imageList) {

    for (Map image : imageList) {
        publishImageAbnToPrd(serviceGroup, serviceName, image.imageName as String, image.tagName as String)
    }
}

/**
 * Perform a retag on a imagePath which is build by the given parameters from abn to prd. 
 */
protected void publishImageAbnToPrd(String serviceGroup, String serviceName, String applicationName, String tagName) {
    String abnImagePath = getExternalImagePathByNameAndTag(serviceGroup, serviceName, TargetSegment.abn, applicationName, tagName)
    publishImageByNameAndTag(abnImagePath, serviceGroup, serviceName, TargetSegment.prd, applicationName, tagName)
}

protected void publishImageFromExternalToInternalRegistry(String serviceGroup, String serviceName, String applicationName) {
    publishImageFromExternalToInternalRegistry(serviceGroup, serviceName, applicationName, generateImageTag())
}

protected void publishImageFromExternalToInternalRegistry(String serviceGroup, String serviceName, String applicationName, String tagName) {
    String externalImagePath = getImagePathForPublishingByNameAndTag(serviceGroup, serviceName, TargetSegment.abn, applicationName, tagName)
    publishImageWithExistenceCheck(externalImagePath, applicationName, tagName, serviceGroup, serviceName, TargetSegment.abn)
}

protected String publishImageFromExternalToInternalRegistry(Map imageMap, String serviceGroup, String serviceName, TargetSegment targetSegment) {
    if(targetSegment == TargetSegment.abn || targetSegment == TargetSegment.prd){
        publishImageWithExistenceCheck(imageMap["path"] as String, imageMap["name"] as String, imageMap["tag"] as String, serviceGroup, serviceName, targetSegment)
        return getInternalImagePathByNameAndTag(serviceGroup, serviceName, targetSegment, imageMap["name"] as String, imageMap["tag"] as String)
    } else {
        throw new IllegalArgumentException("Only ABN and PRD as TargetSegment is supported for publishing images by this method")
    }
}

protected void publishImageWithExistenceCheck(String sourceImagePath, String targetImageName, String targetImageTag, String serviceGroup, String serviceName, TargetSegment targetSegment) {
    String targetRegistry = getExternalDockerRegistry(targetSegment)
    String targetImagePath = getImagePathForRegistryByNameAndTag(targetRegistry, serviceGroup, serviceName, targetSegment, targetImageName, targetImageTag)

    boolean imageAlreadyExists
    switch (targetSegment) {
        case TargetSegment.tst:
            imageAlreadyExists = imageExistsInOpenshiftExternalRegistry(serviceGroup, serviceName, targetImageName, targetImageTag, targetRegistry, targetSegment)
            break;
        case TargetSegment.abn:
        case TargetSegment.prd:
            imageAlreadyExists = imageExistsInOpenshiftInternalRegistry(serviceGroup, serviceName, targetImageName, targetImageTag, targetRegistry, targetSegment)
            break;
        default:
            throw new IllegalArgumentException("TargetSegment is not supported!")
    }
    if(!imageAlreadyExists) {
        tagAndPushImage(serviceGroup, serviceName, targetSegment, sourceImagePath, targetImagePath)
    }
}

/**
 * Returns a map from given docker image path with the keys path, registry, name, repo and tag.
 * Registry will be validated against target segment. If validation fails an exception will be thrown:
 * <p><ul>
 * <li>tst: dev.docker.system.local, prod.docker.system.local, app416020.system.local:5000 and docker-registry-default.cloud.system.local
 * <li>abn/prd: prod.docker.system.local and docker-registry-default.cloud.system.local
 * </ul><p>
 * If given docker image path does not contain a tag name, an exception will be thrown.
 */
public Map validateImagePath(String imagePath, TargetSegment targetSegment){
    def validatedImageMap = [:]

    String[] image = imagePath.split("/")

    if(image.length == 3){
        extractRegistry(image[0], validatedImageMap, targetSegment)
        validatedImageMap["repo"] = image[1]
        extractImageAndTag(image[2], validatedImageMap)
        validatedImageMap["path"] = imagePath
    } else {
        throw new IllegalArgumentException("Image path is not valid! ")
    }

    return validatedImageMap
}

private void extractRegistry(String dockerRegistry, Map image, TargetSegment targetSegment){
    boolean isAllowed = false
    switch (targetSegment) {
        case TargetSegment.tst:
            isAllowed = [DEV_DOCKER_REGISTRY, PROD_DOCKER_REGISTRY, EXT_NAME_OPENSHIFT_DOCKER_REGISTRY, "app416020.system.local:5000"].contains(dockerRegistry)
            break;
        case TargetSegment.abn:
        case TargetSegment.prd:
            isAllowed = [PROD_DOCKER_REGISTRY, EXT_NAME_OPENSHIFT_DOCKER_REGISTRY].contains(dockerRegistry)
            break;

        default:
            throw new IllegalArgumentException("Target segment " + targetSegment + "not supported!")
    }

    if (isAllowed) {
        image["registry"] = dockerRegistry
    } else {
        throw new IllegalArgumentException("Registry " + dockerRegistry + " not allowed for target segment " + targetSegment + "!")
    }
}

private void extractImageAndTag(String imageNameWithTag, Map image){
    String[] nameWithTag = imageNameWithTag.split(":")
    if(nameWithTag.length == 2){
        String imageName = nameWithTag[0]
        String imageTag = nameWithTag[1]
        image["name"] = imageName
        image["tag"] = imageTag
    } else {
        throw new IllegalArgumentException("Image name or tag missing! ")
    }

}

/**
 * Returns the image path for the internal docker regsitry.
 */
protected String getImagePath(String serviceGroup, String serviceName, String imageName, String imageTag, TargetSegment targetSegment) {
    def dockerRegistry = getInternalDockerRegistry(targetSegment)
    return getImagePathForRegistryByNameAndTag(dockerRegistry, serviceGroup, serviceName, targetSegment, imageName, imageTag)
}

/**
 * Imports a list of given docker images from provider source registry to target.
 * Each image will be pulled from given provider registry, tagged and pushed to (si) internal registry.
 */
protected void importImagesFromProviderRegistry(String serviceGroup, String serviceName, List<Map> imageList, String providerRegistry, TargetSegment targetSegment) {
    loginToSDADockerRegistries(serviceGroup)
    loginToProviderDockerRegistry(serviceGroup, providerRegistry)
    for (Map image : imageList) {
        String dockerRegistry = getRegistryForPublishing(targetSegment)
        sourceImagePath = providerRegistry + "/"
        if (!image.repoName.trim().isEmpty()) {
            sourceImagePath += image.repoName + "/"
        }
        sourceImagePath += image.imageName + ":" + image.tagName
        targetImagePath = getImagePathForPublishingByNameAndTag(serviceGroup, serviceName, targetSegment, image.imageName as String, image.tagName as String)
        if(!imageExistsInRegistryToPublish(serviceGroup, serviceName, image.imageName as String, image.tagName as String, dockerRegistry, targetSegment)) {
            importImage(serviceGroup, sourceImagePath, targetImagePath)
        }
    }
}

/**
 * @deprecated use #importImagesFromProviderRegistry instead
 *
 * This method pushes the (si) external image in case of ABN target segment directly to the internal openshift registry.
 * Compliance makes the use of prod.docker.system.local as long-term storage for this target segment obligatory.
 */
protected void importImages(String serviceGroup, String serviceName, List<Map> imageList, String providerRegistry, TargetSegment targetSegment) {
    si_jenkins.printDeprecationWarning("importImages", "importImagesFromProviderRegistry")
    loginToExternalDockerRegistry(serviceGroup, serviceName, targetSegment)
    loginToProviderDockerRegistry(serviceGroup, providerRegistry)
    for (Map image : imageList) {
        dockerRegistry = getExternalDockerRegistry(targetSegment)
        sourceImagePath = providerRegistry + "/"
        if (!image.repoName.trim().isEmpty()) {
            sourceImagePath += image.repoName + "/"
        }
        sourceImagePath += image.imageName + ":" + image.tagName
        targetImagePath = getImagePathForRegistryByNameAndTag(dockerRegistry, serviceGroup, serviceName, targetSegment, image.imageName as String, image.tagName as String)
        if(!imageExistsInRegistry(serviceGroup, serviceName, image.imageName as String, image.tagName as String, dockerRegistry, targetSegment)) {
            importImage(serviceGroup, sourceImagePath, targetImagePath)
        }
    }
}

/**
 * Source docker image will be pulled from registry, tagged and pushed to internal registry
 * prerequisite: login to external secure docker registry for pull/push
 */
private void importImage(String serviceGroup, String sourceImagePath, String targetImagePath) {
    sh """
        docker --config ${serviceGroup} ${DOCKER_TLS_PARAMETER} pull ${sourceImagePath}
        docker --config ${serviceGroup} ${DOCKER_TLS_PARAMETER} tag ${sourceImagePath} ${targetImagePath}
        docker --config ${serviceGroup} ${DOCKER_TLS_PARAMETER} push ${targetImagePath}
    """
}

/**
 * Method returns true if image with given tag already exists in registry, otherwise false
 */
private boolean imageExistsInRegistryToPublish(String serviceGroup, String serviceName, String imageName, String tagName, String dockerRegistry, TargetSegment targetSegment) {
    switch (targetSegment) {
        case TargetSegment.tst:
        case TargetSegment.abn:
            return imageExistsInOpenshiftExternalRegistry(serviceGroup, serviceName, imageName, tagName, dockerRegistry, targetSegment)
        case TargetSegment.prd:
            return imageExistsInOpenshiftInternalRegistry(serviceGroup, serviceName, imageName, tagName, dockerRegistry, targetSegment)

        default:
            throw new IllegalArgumentException("Target segment $targetSegment is not supported for publishing images")
    }
}

/**
 * @deprecated use #imageExistsInRegistryToPublish instead
 */
private boolean imageExistsInRegistry(String serviceGroup, String serviceName, String imageName, String tagName, String dockerRegistry, TargetSegment targetSegment) {
     si_jenkins.printDeprecationWarning("imageExistsInRegistry", "imageExistsInRegistryToPublish")
     if(targetSegment == TargetSegment.tst) {
         rc = sh(returnStatus: true,
            script: """
                curl --silent http://$dockerRegistry/v2/$serviceGroup-$serviceName-${targetSegment.toString()}/$imageName/tags/list | grep $tagName;echo \$?
            """)

     } else {
         rc = sh(returnStatus: true,
            script: """
                curl  --silent -k -H "Authorization: Bearer ${si_openshift.currentSessionToken()}"  https://$dockerRegistry/v2/$serviceGroup-$serviceName-${targetSegment.toString()}/$imageName/tags/list | grep $tagName;echo \$?
            """)
     }

    if (rc != 0) {
        println "Image $dockerRegistry/$serviceGroup-$serviceName-${targetSegment.toString()}/$imageName:$tagName exists NOT in registry"
        return false
    } else {
        println "Image $dockerRegistry/$serviceGroup-$serviceName-${targetSegment.toString()}/$imageName:$tagName exists in registry, proccessing will skipped"
        return true
    }

}

/**
 * Returns true, when image already exists in given openshift external docker registry, false otherwise.
 */
private boolean imageExistsInOpenshiftExternalRegistry(String serviceGroup, String serviceName, String imageName, String tagName, String dockerRegistry, TargetSegment targetSegment) {
    String imageRepo =  "$serviceGroup-$serviceName"
    if(targetSegment == TargetSegment.tst){
        imageRepo += "-${targetSegment.toString()}"
    }
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'anonymous-docker-registry', usernameVariable: 'ANONYMOUS_DOCKER_REGISTRY_USER', passwordVariable: 'ANONYMOUS_DOCKER_REGISTRY_PASSWORD']]) {
        rc = sh(returnStatus: true,
                script: """
                    set +x
                    curl --silent https://${ANONYMOUS_DOCKER_REGISTRY_USER}:${ANONYMOUS_DOCKER_REGISTRY_PASSWORD}@$dockerRegistry/v2/$imageRepo/$imageName/tags/list | grep '"$tagName"'
                """)
    }

    if (rc != 0) {
        println "Image $dockerRegistry/$imageRepo/$imageName:$tagName does NOT exist in registry"
        return false
    } else {
        println "Image $dockerRegistry/$imageRepo/$imageName:$tagName already available in registry, proccessing will skipped"
        return true
    }
}

/**
 * Returns true, when image already exists in given openshift internal docker registry, false otherwise.
 */
private boolean imageExistsInOpenshiftInternalRegistry(String serviceGroup, String serviceName, String imageName, String tagName, String dockerRegistry, TargetSegment targetSegment) {
    loginToExternalDockerRegistry(serviceGroup, serviceName, targetSegment)

    rc = sh(returnStatus: true,
            script: """
                set +x
                curl  --silent -k -H "Authorization: Bearer ${si_openshift.currentSessionToken()}"  https://$dockerRegistry/v2/$serviceGroup-$serviceName-${targetSegment.toString()}/$imageName/tags/list | grep '"$tagName"'
            """)

    if (rc != 0) {
        println "Image $dockerRegistry/$serviceGroup-$serviceName-${targetSegment.toString()}/$imageName:$tagName does NOT exist in registry"
        return false
    } else {
        println "Image $dockerRegistry/$serviceGroup-$serviceName-${targetSegment.toString()}/$imageName:$tagName already available in registry, proccessing will skipped"
        return true
    }
}

/**
 * Login to an external docker registry.The docker registry is outside the SI.
 */
private void loginToProviderDockerRegistry(String serviceGroup, String providerRegistry) {
    withCredentials([usernamePassword(credentialsId: "${serviceGroup}-registry-ext", passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
        sh """
            docker --config ${serviceGroup} ${DOCKER_TLS_PARAMETER} login -u '${USERNAME}' -p '${PASSWORD}' ${providerRegistry}
        """        
    }  
}

/**
 * Run an arbitrary docker image on one of the docker build slaves.
 *
 * This method is only meant to be used within the shared library and not
 * directly by projects.
 */
protected void runContainer(String serviceGroup, String options, String imageName, String command) {
    sh """
        docker --config ${serviceGroup} ${DOCKER_TLS_PARAMETER} run ${options} ${imageName} ${command}
    """
}

/**
 * Execute the given docker image on one of the docker build slaves.
 *
 * Returns the output to stdout as a string.
 */
public String execContainer(String serviceGroup, String options, String imageName, String command) {
    loginToSDADockerRegistries(serviceGroup)
    return sh(
            returnStdout: true,
            script: """
                    docker --config ${serviceGroup} ${DOCKER_TLS_PARAMETER} run ${options} ${imageName} ${command}
                    """
    ).trim()
}

/**
 * Reads the Dockerfile in the current working directory and returns all
 * docker image referenced in "FROM" lines.
 *
 * Returns a List with one entry per "FROM" line. Each entry is a map with
 * the keys "registry" (server/host), "repository" (folder/image) and
 * "tag".
 */
public List<Map<String, String>> readBaseImagesFromDockerfile() {
    return readBaseImagesFromDockerfile("Dockerfile")
}

/**
 * Reads the given Dockerfile and returns all docker image referenced in
 * "FROM" lines.
 *
 * Returns a List with one entry per "FROM" line. Each entry is a map with
 * the keys "registry" (server/host), "repository" (folder/image) and
 * "tag".
 */
public List<Map<String, String>> readBaseImagesFromDockerfile(String dockerfile) {
    baseImages = []

    dockerfileContents = readFile file: "${dockerfile}"
    pattern = /[Ff][Rr][Oo][Mm]\s*(?:([\w\-]+\.[\w\-\.]+(?::\d+)?)\/)?([^\s:]+)(?::([^\s]+))?/
    matches = (dockerfileContents =~ pattern).findAll()
    for (String[] match : matches) {
        baseImages.add([
            registry: (match[1] ? match[1] : "docker.io"),
            repository: match[2],
            tag: (match[3] ? match[3] : "latest")
        ])
    }

    return baseImages
}

/**
 * Internal use only. May patch a Dockerfile to use the recent base images.
 *
 * When a Dockerfile uses comment '#USE_LATEST' before 'FROM' lines, the
 * specified TAG for base image will be overridden, if possible.
 *
 * Currently supports simple numeric tags (e.g. '107') and semantic versioning
 * tags (e.g. '1.2.3'). Others will break the build to inform about the
 * incorrect usage of '#USE_LATEST'.
 */
private void patchDockerfileToUseLatest(String context, String dockerfile, String serviceGroup) {
    images = extractImageDefinitionsFromDockerfile(dockerfile)
    extractDockerLoginsFromProjectlocalDockerConfig(dockerfile, serviceGroup, images)
    extractLatestTagFromDockerRegistry(images)

    for (String image : images) {
        search = "${image['image']}:${image['tag']}"
        replace = "${image['image']}:${image['latest_tag']}"
        server = ""
        if (image['server'] != null && image['server'].trim().length() > 0)
            server = "${image['server']}/"

        label = "LABEL de.signaliduna.image.base=\"${server}${replace}\""

        replaceString = "${replace}\n${label}"
        fileContent = readFile("${env.WORKSPACE}/${dockerfile}")
        
        sh """
            cp "${dockerfile}" "${dockerfile}.bak"
        """
        
        if(fileContent.contains(replaceString)){
            println("Label '${label}' for image '${image['image']}' is already present in Dockerfile")
            println("Replacing '${image['image']}:${image['tag']}' with latest '${image['image']}:${image['latest_tag']}'.")
            sh """ 
                sed --in-place 's|${search}|${replace}|g' "${dockerfile}" 
            """
        }else{
            println("Label '${label}' for image '${image['image']}' is NOT present in Dockerfile.")
            println("Replacing '${image['image']}:${image['tag']}' with latest '${image['image']}:${image['latest_tag']}'.")
            println("Adding '${label}'")
            sh """ 
                sed --in-place 's|${search}|${replace}\\n${label}|g' "${dockerfile}" 
            """
        }

    }
}

/**
 * Internal use only. Extracts all image information from the Dockerfile that
 * needs to be patched, i.e. FROM lines annotated with '# USE_LATEST'.
 */
private List extractImageDefinitionsFromDockerfile(String dockerfile) {
    result = []
    
    annotatedImages = extractAnnotatedBaseImagesFromDockerfile(dockerfile)

    for (String annotatedImage : annotatedImages) {
        pattern = /^([\w\-]+\.[\w\-\.]+(?::\d+)?\/)?([\w\-\/]+):?(.*)$/
        matches = (annotatedImage =~ pattern).findAll()
        
        for (String[] match : matches) {
            server = (match[1] ? match[1] : "docker.io")
            image = match[2]
            tag = match[3]
            vcomp = checkSupportedTagFormat(tag, dockerfile)

            if (server[-1] == '/') server = server[0..-2]

            result.add(
                [ 'server': server, 'image': image, 'tag': tag, 'vcomp': vcomp]
            )
        }
    }

    return result
}

/**
 * Internal use only. Reads a Dockerfile and finds all FROM-lines annotated
 * with '# USE_LATEST'.
 */
private String[] extractAnnotatedBaseImagesFromDockerfile(String dockerfile) {
    dockerfileContents = readFile file: "${dockerfile}"
    pattern = /#\s*USE_LATEST\s+FROM\s+([^\s]+)/
    matches = (dockerfileContents =~ pattern).findAll()
    images = []
    for (String[] match : matches)
        images.add(match[1])
    return images
}

/**
 * Internal use only. Checks a given tag if it uses a supported pattern.
 */
private VersionComparator checkSupportedTagFormat(String tag, String dockerfile) {
    for (VersionComparator vcomp : TAG_FORMATS)
        if ((tag =~ vcomp.pattern).matches())
            return vcomp

    throw new RuntimeException("The usage of '# USE_LATEST' in Dockerfile ('${dockerfile}') is only allowed for simple numeric tags or semantic version tags. The encountered tag ('${tag}') is not supported!")
}

/**
 * Internal use only. Augments image information with login data for each used
 * registry if possible. Login data is taken from the docker config create by
 * `docker login ...`.
 */
private void extractDockerLoginsFromProjectlocalDockerConfig(String dockerfile, String serviceGroup, List images) {
    dockerLogins = readJSON file: "${serviceGroup}/config.json"
    auths = dockerLogins['auths']

    images.each { image ->
        dockerLogins['auths'].each { registry_name, registry_info  ->
            if (registry_name.contains(image['server'])) image['auth'] = registry_info['auth']
        }
        if (!image.containsKey('auth')) {
            server = image['server']
            throw new RuntimeException("Encountered '# USE_LATEST' in Dockerfile ('${dockerfile}') for registry ('${server}'), but no active login was found! You're probably trying to use it with some external/unsupported registry.")
        }
    }
}

/**
 * Internal use only. Contacts the source docker registries for each image
 * annotated with '# USE_LATEST' and determines the latest tag.
 */
private void extractLatestTagFromDockerRegistry(List images) {
    selectAuthTypeForImageData(images)

    for (def image : images) {
        sh """
            set +x # don't print the token in the jenkins log.
            curl --silent --header "Authorization: ${image['authMethod']} ${image['auth']}" "https://${image['server']}/v2/${image['image']}/tags/list" > .dockertags
        """
        jsonData = (readJSON(file: ".dockertags"))['tags']

        vcomp = image['vcomp']
        image['latest_tag'] = image['tag']

        for (def tag : jsonData)
            if (vcomp.compare(image['latest_tag'], tag) < 0) image['latest_tag'] = tag
    }
}

/**
 * Internal use only. Tries to guess the HTTP authentication method for each
 * image. If the token contains two dots ('.') it's assumed to be a JWT and
 * will be transmitted as a 'Bearer' token. Otherwise, it's transmitted as
 * 'Basic' auth data.
 */
private void selectAuthTypeForImageData(List images) {
    for (def image : images) {
        // JWTs are three base64-strings concatenated with 2x '.' characters.
        if (image['auth'].split("\\.").length == 2)
            image['authMethod'] = 'Bearer'
        else
            image['authMethod'] = 'Basic'
    }
}

/**
 * Internal use only.
 */
abstract class VersionComparator implements Comparator<String> {
    public final String name;
    public final String pattern;

    public VersionComparator(String name, String pattern) {
        this.name = name
        this.pattern = pattern
    }
}

/**
 * Internal use only. Compares simple numeric docker tags.
 */
class SimpleVersionComparator extends VersionComparator {
    public SimpleVersionComparator(String name, String pattern) {
        super(name, pattern)
    }

    public int compare(String o1, String o2) {
        int i1 = Integer.parseInt( (o1 =~ pattern).findAll()[0][1] )
        int i2 = Integer.parseInt( (o2 =~ pattern).findAll()[0][1] )
        return i1 - i2
    }
}

/**
 * Internal use only. Compares semantic version docker tags.
 */
class SemanticVersionComparator extends VersionComparator {
    public SemanticVersionComparator(String name, String pattern) {
        super(name, pattern)
    }

    public int compare(String o1, String o2) {
        def m1 = (o1 =~ pattern).findAll()
        def m2 = (o2 =~ pattern).findAll()

        def major1 = Integer.parseInt( m1[0][1] )
        def major2 = Integer.parseInt( m2[0][1] )
        def minor1 = Integer.parseInt( m1[0][2] )
        def minor2 = Integer.parseInt( m2[0][2] )
        def patch1 = Integer.parseInt( m1[0][3] )
        def patch2 = Integer.parseInt( m2[0][3] )

        if (major1 != major2) return major1 - major2
        if (minor1 != minor2) return minor1 - minor2
        if (patch1 != patch2) return patch1 - patch2
        return 0
    }
}
