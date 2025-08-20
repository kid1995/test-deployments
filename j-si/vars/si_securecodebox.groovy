import de.signaliduna.TargetSegment

/**
 * Deploy secureCodeBox scan-types from the resources folder to the clusters namespace
 */
public void deployScanDependencies(serviceGroup, serviceName, targetSegment, appSecret) {
    echo "Installing secureCodeBox scan dependences (scanType, parseDefinition cutom resources & configMap) into the apps namespace"

    si_openshift.login(serviceGroup, serviceName, targetSegment)

    sh """
        set +x # don't divulge secrets by accident
        echo "--> oc apply -f ${resourcePath("secureCodeBox/static-manifests.yaml")}"
        oc apply --config cli-context -f ${resourcePath("secureCodeBox/static-manifests.yaml")}
    """

    withCredentials([usernamePassword(credentialsId: appSecret, usernameVariable: 'APP_USERNAME', passwordVariable: 'APP_PASSWORD')]) {
        si_openshift.processTemplate(resourcePath("secureCodeBox/scan-config.yaml"), [
            APP_USERNAME:           APP_USERNAME,
            APP_PASSWORD:           APP_PASSWORD,
        ])
    }
}

private String resourcePath(String resourceFile) {
    def filePath = "$env.workspace/$resourceFile"
    writeFile(file: filePath, text: libraryResource("de/signaliduna/$resourceFile"))
    return filePath
}