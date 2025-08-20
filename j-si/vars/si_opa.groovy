import de.signaliduna.OpaVersion
import de.signaliduna.TargetSegment

void printDeprecationWarning(String deprecatedFunction, String text) {
    echo "┳┻|\n┻┳|\n┳┻| _     HEADS UP! The ${deprecatedFunction} function in your Jenkinsfile is deprecated.\n┻┳| •.•)\n┳┻|⊂ﾉ     ${text}\n┻┳|\n┳┻|"
}

/**
 * Verifies the OPA policies
 */
void verify(policy, serviceGroup, serviceName, OpaVersion opaVersion = OpaVersion.v0) {
    def dockerPath = isPolicyConfig(policy)
        ? writeOpaDockerfileForPolicyConfig(policy, opaVersion)
        : writeOpaDockerfileForPolicyPath(policy, opaVersion)
    def opaImageName = getOpaImageName(opaVersion)
    def imageName = si_docker.buildImageWithDockerfileByNameAndTag('.', dockerPath, serviceGroup, serviceName, TargetSegment.tst, opaImageName, getImageTag(opaVersion))

    si_docker.runContainer(serviceGroup, '--rm', imageName, 'test /policy -v')
}

/**
 * Deployment of OPA with the given policies as config file or as path to policies including data
 */
void deploy(
        policy,
        serviceGroup,
        serviceName,
        targetSegment,
        logLevel = null,
        initialPolicy = null,
        requestsCpu = "20m",
        requestsMemory = "50Mi",
        limitsCpu = "1500m",
        limitsMemory = "2500Mi",
        OpaVersion opaVersion = OpaVersion.v0
    )
{
    buildAndPublishImage(policy, serviceGroup, serviceName, targetSegment, initialPolicy, opaVersion)
    deployImage(
        policy,
        serviceGroup,
        serviceName,
        targetSegment,
        logLevel,
        initialPolicy,
        requestsCpu,
        requestsMemory,
        limitsCpu,
        limitsMemory,
        opaVersion
    )
}

/**
 * Prepare an OPA-Image for later deployment with the given policies as config file or as path to policies including data
 * @see #deployImage
 */
void buildAndPublishImage(
        policy,
        serviceGroup,
        serviceName,
        targetSegment,
        initialPolicy = null,
        OpaVersion opaVersion = OpaVersion.v0
    )
{
    println "DEBUG | si_opa.buildAndPublishImage(${policy}, ${serviceGroup}, ${serviceName}, ${targetSegment}, ${initialPolicy}, ${opaVersion})"

    def opaImageName = getOpaImageName(opaVersion)
    println "DEBUG | si_opa.buildAndPublishImage: opaImageName=${opaImageName}"

    def isPolicyConfig = isPolicyConfig(policy)
    println "DEBUG | si_opa.buildAndPublishImage: isPolicyConfig=${isPolicyConfig}"

    def imageTag = getImageTag(opaVersion)
    println "DEBUG | si_opa.buildAndPublishImage: imageTag=${imageTag}"

    if (targetSegment == TargetSegment.prd) {
        def externalImagePath = si_docker.getExternalImagePathByNameAndTag(
                serviceGroup,
                serviceName,
                TargetSegment.abn,
                opaImageName,
                imageTag
        )
        si_docker.publishImageByNameAndTag(
                externalImagePath,
                serviceGroup,
                serviceName,
                targetSegment,
                opaImageName,
                imageTag
        )
    } else {
        def dockerPath = isPolicyConfig
            ? initialPolicy != null
                ? writeOpaDockerfileForPolicyConfigAndDefaultPolicy(policy, initialPolicy, opaVersion)
                : writeOpaDockerfileForPolicyConfig(policy, opaVersion)
            : writeOpaDockerfileForPolicyPath(policy, opaVersion)
        println "DEBUG | si_opa.buildAndPublishImage: dockerPath=${dockerPath}"
        si_docker.buildImageWithDockerfileByNameAndTag(
                '.',
                dockerPath,
                serviceGroup,
                serviceName,
                targetSegment,
                opaImageName,
                imageTag
        )
    }

    def branchName = si_openshift.filterBranchName(si_git.branchName())
    println "DEBUG | si_opa.buildAndPublishImage: branchName=${branchName}"

    def serviceNamespace = si_openshift.getServiceNamespace(serviceGroup, serviceName, targetSegment)
    println "DEBUG | si_opa.buildAndPublishImage: serviceNamespace=${serviceNamespace}"

    si_openshift.login(serviceGroup, serviceName, targetSegment)
    si_openshift.processTemplate(resourcePath("opa-config.yaml"), [
            "BRANCH_NAME"      : branchName,
            "SERVICE_NAMESPACE": serviceNamespace
    ])

    // This step is necessary cause deployment has to be done from internal openshift registry
    // and the images are only pushed to external registry during build
    if (targetSegment == TargetSegment.abn) {
        si_docker.publishImageFromExternalToInternalRegistry(serviceGroup, serviceName, opaImageName, imageTag)
    }
}

/**
 * Deployment of prebuild OPA-Image with the given policies as config file or as path to policies including data
 * @see #buildAndPublishImage
 */
void deployImage(
        policy,
        serviceGroup,
        serviceName,
        targetSegment,
        logLevel,
        initialPolicy,
        requestsCpu,
        requestsMemory,
        limitsCpu,
        limitsMemory,
        OpaVersion opaVersion = OpaVersion.v0
    )
{
    println "DEBUG | si_opa.deployImage(${policy}, ${serviceGroup}, ${serviceName}, ${targetSegment}, ${logLevel}, ${initialPolicy}, ${requestsCpu}, ${requestsMemory}, ${limitsCpu}, ${limitsMemory}, ${opaVersion})"

    def opaImageName = getOpaImageName(opaVersion)
    println "DEBUG | si_opa.deployImage: opaImageName=${opaImageName}"

    def isPolicyConfig = isPolicyConfig(policy)
    println "DEBUG | si_opa.deployImage: isPolicyConfig=${isPolicyConfig}"

    def numReplicas = targetSegment == TargetSegment.prd ? 2 : 1

    def branchName = si_openshift.filterBranchName(si_git.branchName())
    def projectUrl = si_openshift.getProjectUrl(serviceGroup, serviceName, targetSegment)
    def internalImagePath = si_docker.getInternalImagePathByNameAndTag(serviceGroup, serviceName, targetSegment, opaImageName, getImageTag(opaVersion))
    def resourceName = si_openshift.getApplicationResourceName(opaImageName, targetSegment)
    def opaServiceConfig = [
        "BRANCH_NAME"       : branchName,
        "IMAGE_NAME"        : internalImagePath,
        "COMMIT_ID"         : si_git.shortCommitId(),
        "RESOURCE_NAME"     : resourceName,
        "PROJECT_URL"       : projectUrl,
        "STAGE_PROPERTY_MAP": si_openshift.processStageConfigMap(targetSegment),
        "NUM_REPLICAS"      : numReplicas,
        'REQUESTS_CPU'      : requestsCpu,
        'REQUESTS_MEMORY'   : requestsMemory,
        'LIMITS_CPU'        : limitsCpu,
        'LIMITS_MEMORY'     : limitsMemory,
    ]

    if (logLevel != null) {
        opaServiceConfig['ENV_LOG_LEVEL'] = logLevel
    }

    if (isPolicyConfig) {
        if (initialPolicy != null) {
            opaServiceConfig['READINESS_PROBE_URI'] = '/health'
        }
        def opaServiceConfigFilename = getOpaServiceConfigFilename(opaVersion)
        si_openshift.processTemplate(resourcePath(opaServiceConfigFilename), opaServiceConfig)
    } else {
        def opaServiceFilename = getOpaServiceFilename(opaVersion)
        si_openshift.processTemplate(resourcePath(opaServiceFilename), opaServiceConfig + [
            "ENV_DATA_FILE": "/data/${targetSegment}.json"
        ])
    }
    si_openshift.deployContainer(serviceGroup, serviceName, targetSegment, resourceName)

    if(targetSegment == TargetSegment.tst) {
        def templateParameters = [
                "BRANCH_NAME"       : branchName,
                "RESOURCE_NAME"     : resourceName,
                "PROJECT_URL"       : projectUrl
        ]
        si_openshift.processTemplate(resourcePath("opa-route.yaml"), templateParameters)
    }
}

private String getImageTag(OpaVersion opaVersion) {
    def opaBaseImageVersion = getOpaBaseImageVersion(opaVersion)
    return "${si_docker.generateImageTag()}-${opaBaseImageVersion}"
}

private boolean isPolicyConfig(policy) {
    return policy.toLowerCase().endsWith(".yml") || policy.toLowerCase().endsWith(".yaml")
}

private String writeOpaDockerfileForPolicyConfig(policyConfig, OpaVersion opaVersion) {
    def opaBaseImageName = getOpaBaseImageName(opaVersion)
    def opaBaseImageVersion = getOpaBaseImageVersion(opaVersion)
    return writeTemporaryDockerfile("""
        FROM $opaBaseImageName:$opaBaseImageVersion
        COPY $policyConfig /config.yaml
        COPY $policyConfig /data/.keep
    """)
}

private String writeOpaDockerfileForPolicyConfigAndDefaultPolicy(configfile, policyfile, OpaVersion opaVersion) {
    def opaBaseImageName = getOpaBaseImageName(opaVersion)
    def opaBaseImageVersion = getOpaBaseImageVersion(opaVersion)
    return writeTemporaryDockerfile("""
        FROM $opaBaseImageName:$opaBaseImageVersion
        COPY $configfile /config.yaml
        COPY $policyfile /data/default.rego
    """)
}

private String writeOpaDockerfileForPolicyPath(policyPath, OpaVersion opaVersion) {
    TargetSegment.values().each {
        def envFile = "${policyPath}/${it}.json"
        if (!fileExists(envFile)) {
            writeFile file: envFile, text: "{}"
        }
    }
    def opaBaseImageName = getOpaBaseImageName(opaVersion)
    def opaBaseImageVersion = getOpaBaseImageVersion(opaVersion)
    def dockerfile = """
        FROM $opaBaseImageName:$opaBaseImageVersion
        COPY $policyPath/*.rego /policy/
        COPY $policyPath/*.json /data/
    """

    return writeTemporaryDockerfile(dockerfile)
}

private String writeTemporaryDockerfile(Dockerfile) {
    def dockerPath = '__Dockerfile-OPA__'
    writeFile file: dockerPath, text: Dockerfile
    return dockerPath
}

private String resourcePath(String resourceFile) {
    def filePath = "$env.workspace/$resourceFile"
    writeFile(file: filePath, text: libraryResource("de/signaliduna/$resourceFile"))
    return filePath
}

public String getOpaImageName(OpaVersion opaVersion) {
    switch (opaVersion) {
        case OpaVersion.v0:
            return "opa"
        case OpaVersion.v1:
            return "opa-v1"
        default:
            throw new IllegalArgumentException("Unknown OpaVersion $opaVersion.")
    }
}

public String getOpaBaseImageName(OpaVersion opaVersion) {
    switch (opaVersion) {
        case OpaVersion.v0:
            return "prod.docker.system.local/baseimages/sda-opa-alpine"
        case OpaVersion.v1:
            return "prod.docker.system.local/baseimages/sda-opa-v1-alpine"
        default:
            throw new IllegalArgumentException("Unknown OpaVersion $opaVersion.")
    }
}

public String getOpaBaseImageVersion(OpaVersion opaVersion) {
    switch (opaVersion) {
        case OpaVersion.v0:
            return "1"
        case OpaVersion.v1:
            return "2"
        default:
            throw new IllegalArgumentException("Unknown OpaVersion $opaVersion.")
    }
}

private String getOpaServiceFilename(OpaVersion opaVersion) {
    switch (opaVersion) {
        case OpaVersion.v0:
            return "opa-service.yaml"
        case OpaVersion.v1:
            return "opa-v1-service.yaml"
        default:
            throw new IllegalArgumentException("Unknown OpaVersion $opaVersion.")
    }
}

private String getOpaServiceConfigFilename(OpaVersion opaVersion) {
    switch (opaVersion) {
        case OpaVersion.v0:
            return "opa-service-configfile.yaml"
        case OpaVersion.v1:
            return "opa-v1-service-configfile.yaml"
        default:
            throw new IllegalArgumentException("Unknown OpaVersion $opaVersion.")
    }
}
