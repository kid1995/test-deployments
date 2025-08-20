import de.signaliduna.TargetSegment
import groovy.transform.Field


@Field
private static final String GIT_CD_REPO_URL = "ssh://git@git.system.local:7999/sdasvcdeploy"

@Field
private static final String ACCOUNT_ID_NONPROD = "071489271236" 

@Field
private static final String ACCOUNT_ID_PROD = "not suppored yet" 


/**
 * maps openshift stages to AWS EKS stages
 */
public String alpha_getStage(TargetSegment targetSegment){
    switch (targetSegment) {
            case TargetSegment.tst:
                return "dev"
            case TargetSegment.abn:
                return "test"
            default:
                throw new IllegalArgumentException("Target segment $targetSegment is not supported.")
    }
}

/**
 * Deploys the service to AWS
 */
public alpha_publishToCdRepo(String imagePath, String serviceGroup, String serviceName, String applicationName, TargetSegment targetSegment) {
    targetImagePath = alpha_pushImageToAwsDockerRegistry(imagePath, serviceGroup, serviceName, applicationName, targetSegment)

    manifestSourceFolder = "${WORKSPACE}/aws"
    manifestDestinationFolder = "${WORKSPACE}/${serviceGroup}-${serviceName}"

    deploymentFileSource = "${manifestSourceFolder}/deployment.yaml"

    def data = readFile(file: deploymentFileSource)
    replacedData= data.replaceAll("SERVICE_IMAGE", targetImagePath)
    writeFile(file: deploymentFileSource, text: replacedData)
    
    si_git.alpha_clone("${GIT_CD_REPO_URL}/${serviceGroup}-${serviceName}.git", alpha_getStage(targetSegment),
        manifestDestinationFolder )

    dir(manifestDestinationFolder){
        sh "cp ${manifestSourceFolder}/* ."
        si_git.alpha_addAndPush("autoPush from Jenkins for Argo CD Pipeline")
    }
}

private String getAccountId(TargetSegment targetSegment) {

    switch (targetSegment) {
            case TargetSegment.tst:
            case TargetSegment.abn:
                return ACCOUNT_ID_NONPROD
            case TargetSegment.prd:
                throw new Exception("Target segment $targetSegment is not supported yet.")
            default:
                throw new IllegalArgumentException("Target segment $targetSegment is not supported.")
    }
}
private String alpha_getEcrToken(String serviceGroup){


    withCredentials([usernamePassword(credentialsId: 'siproxy', usernameVariable: 'HTTP_PROXY_USERNAME', passwordVariable: 'HTTP_PROXY_PASSWORD'),
                     usernamePassword(credentialsId: 'aws-svc-sda-jenkins-nonprod', usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY')]) {

        String token = sh (
            returnStdout: true,
            script: """ 
                docker --config ${serviceGroup} ${si_docker.DOCKER_TLS_PARAMETER} run --rm  \
                    -e http_proxy=http://${HTTP_PROXY_USERNAME}:${HTTP_PROXY_PASSWORD}@proxy.system.local:80/ \
                    -e https_proxy=http://${HTTP_PROXY_USERNAME}:${HTTP_PROXY_PASSWORD}@proxy.system.local:80/ \
                    -e AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID} \
                    -e AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY} \
                    amazon/aws-cli ecr get-login-password --region eu-central-1
            """
        ).trim()

        return token
    }
}


/**
 * Pushes the source image  to amazon ECR.
 * 
 * e.g.
 * sourceImageName: dev.docker.system.local/sdasi-blueprint-tst/backend:6d844a6.5
 * targetImageName: 071489271236.dkr.ecr.eu-central-1.amazonaws.com/sda-services/dev/sdasi/blueprint:backend-6d844a6.5
 */
public String alpha_pushImageToAwsDockerRegistry(String sourceImagePath, String serviceGroup, String serviceName, String applicationName, TargetSegment targetSegment) {

    print("ImagePath: $sourceImagePath")

    aws_account = "${getAccountId(targetSegment)}.dkr.ecr.eu-central-1.amazonaws.com"
    targetImagePath = "${aws_account}/sda-services/${alpha_getStage(targetSegment)}/${serviceGroup}/${serviceName}:${applicationName}-${sourceImagePath.substring(sourceImagePath.lastIndexOf(":")+1)}"

    print("pull/tag image and push to amazon ecr")
    si_docker.loginToSDADockerRegistries(serviceGroup)

    ecrToken = alpha_getEcrToken(serviceGroup)

    sh """
        docker --config ${serviceGroup} ${si_docker.DOCKER_TLS_PARAMETER} pull ${sourceImagePath}
        docker --config ${serviceGroup} ${si_docker.DOCKER_TLS_PARAMETER} tag ${sourceImagePath} ${targetImagePath}

        docker --config ${serviceGroup} ${si_docker.DOCKER_TLS_PARAMETER} login --username AWS --password ${ecrToken} ${aws_account}
        docker --config ${serviceGroup} ${si_docker.DOCKER_TLS_PARAMETER} push ${targetImagePath}
        """
   
    return targetImagePath
}
