import de.signaliduna.TargetSegment
import groovy.transform.Field

@Field
private static final String DEV_DOCKER_REGISTRY = "dev.docker.system.local"
@Field
private static final String PROD_DOCKER_REGISTRY = "prod.docker.system.local"

/**
 * Services will be started on given Server by using docker-compose.
 * 
 * @param serverAddress
 *   Name of the address where the services will be started, e.g 'apa319001'	
 * @param appName
 *   Name of the application, e.g. 'automationhero'
 * @param images
 *   List of images, defined in Jenkinsfile
 * @param stage
 *   If further artifacts available, e.g 'env.properties'.
 *   It will be expected a folder with same name how the stage in the working 
 *   directory, e.g 'tst' 
 * @param composeFileName
 *   The file name of the docker compose definition.
 *   This parameter is optional and can be overriden with an other file name.
 */
public void deploy(String serverAddress, String appName, Map images, String stage, String composeFileName = "docker-compose.yml") {
    serverGroup = "apx${serverAddress.substring(3,6)}"
    configFileName = "env.properties"
    envList = ["BDP_ENVFILE=/home/cdp/$appName/$configFileName"]

    // Add credential for cdp User
    credentialList = [sshUserPrivateKey(credentialsId: "$serverGroup-cdp", keyFileVariable: 'SSH_KEY', usernameVariable: 'USERNAME')]

    // Add credentials for docker registry / build image list
    if (serverAddress.startsWith('apa')) {
        credentialList.add(usernamePassword(credentialsId: 'anonymous-docker-registry', usernameVariable: 'ANONYMOUS_DOCKER_REGISTRY_USER', passwordVariable: 'ANONYMOUS_DOCKER_REGISTRY_PASSWORD'))
        images.each{key, value -> 
            envList.add("BDP_IMAGE_$key=$DEV_DOCKER_REGISTRY/$serverGroup-$appName-tst/${value.get(0).imageName}:${value.get(0).tagName}")}
    } else {
        credentialList.add(usernamePassword(credentialsId: 'base_docker_platform_user', usernameVariable: 'PUSH_PROD_DOCKER_REGISTRY_USER', passwordVariable: 'PUSH_PROD_DOCKER_REGISTRY_PASSWORD'))
        images.each{key, value -> 
            envList.add("BDP_IMAGE_$key=$PROD_DOCKER_REGISTRY/$serverGroup-$appName/${value.get(0).imageName}:${value.get(0).tagName}")}
    }

    withCredentials(credentialList) {
        withEnv(envList){

            if (serverAddress.startsWith('apa')) {
                sh """
                    ssh -tt  -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -i ${SSH_KEY} ${USERNAME}@${serverAddress} '
                        sudo docker login -u ${ANONYMOUS_DOCKER_REGISTRY_USER} -p ${ANONYMOUS_DOCKER_REGISTRY_PASSWORD} ${DEV_DOCKER_REGISTRY}
                    '
                """    
            } else {
                sh """
                    ssh -tt  -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -i ${SSH_KEY} ${USERNAME}@${serverAddress} '
                        sudo docker login -u ${PUSH_PROD_DOCKER_REGISTRY_USER} -p ${PUSH_PROD_DOCKER_REGISTRY_PASSWORD} ${PROD_DOCKER_REGISTRY}
                    '
                """
            }

            sh """
                mkdir -p build

                cp docker/$stage/${configFileName} build/${configFileName}
                envsubst < docker/${composeFileName} > build/${composeFileName}

                # Create destination folder for artifacts
                ssh -tt -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -i ${SSH_KEY} ${USERNAME}@${serverAddress} 'mkdir -p ~/$appName'

                # Copy all artifacts for deployment
                scp -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -i ${SSH_KEY} -r build/* ${USERNAME}@${serverAddress}:~/$appName/

                # Create si bridge betwork if not exists
                ssh -tt -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -i ${SSH_KEY} ${USERNAME}@${serverAddress} '
                    NETWORK_NAME="si"
                    EXISTING_NETWORKS=\$(sudo docker network ls | grep \$NETWORK_NAME || true )
                    if [ -z "\$EXISTING_NETWORKS" ]; then
                        sudo docker network create --subnet=172.18.128.0/17 si || true
                    fi
                '

                # Start Services
                ssh -tt  -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -i ${SSH_KEY} ${USERNAME}@${serverAddress} '
                    cd ${appName}
                    sudo docker-compose down
                    sudo docker-compose up -d
                '
            """.stripIndent()
        }
    }
}