import de.signaliduna.TargetSegment
import java.util.regex.Pattern

void deployDB(String serviceGroup, String serviceName, TargetSegment targetSegment, String mongodbVersion, int mongodbBackupKeep, String backupSchedule, String dbSize="M") {
    fillStaticParameters(serviceGroup, serviceName, targetSegment)
    MONGODB_BACKUP_KEEP       = checkMongodbBackupKeep(mongodbBackupKeep)
    BACKUP_SCHEDULE           = checkBackupSchedule(backupSchedule)
    MONGODB_VERSION           = mongodbVersion

    switch(mongodbVersion) {
        case "6.0":
            mongodbImage = publishImageToInternalRegistry(serviceGroup, serviceName, targetSegment, "prod.docker.system.local/baseimages/", mongodbVersion, "replset", "12")
            break
        default:
            throw new IllegalArgumentException("Parameter mongodb_version ${mongodbVersion} is not valid. MongoDB ${mongodbVersion} will not be deployed.")
            break
    }

    si_openshift.login(serviceGroup, serviceName, targetSegment)

    // Rolling Upgrade, if MongoDB already exists for this branch and size is changed. Deploy MongoDB otherwise. For test just deploying a new Replicaset
    if (mongoDBExistsInProject() && !isDeploymentForFeatureBranch(targetSegment)) {
        if (isSizeChanged(targetSegment, dbSize)) {
            performRollingUpgrade(serviceGroup, serviceName, targetSegment, mongodbImage, dbSize)
        } else {
            /* this code block can be kept for the next version transition phase
            if (isVersion("50")) {
                try {
                    setFeatureCompatibility(serviceGroup, serviceName, targetSegment, "5.0")
                } catch (Exception e) {
                    echo "Error: Could not set feature compatibility."
                    throw e
                }
            }*/
            deployReplicaSet(serviceGroup, serviceName, targetSegment, mongodbImage, dbSize)
            // set the compatibility version to the deployed MongoDB version
            setFeatureCompatibility(serviceGroup, serviceName, targetSegment, mongodbVersion)
            deployExporter(serviceGroup, serviceName, targetSegment)
        }
    } else {
        deleteOldResources(serviceGroup, serviceName, targetSegment, dbSize, false)
        deployReplicaSet(serviceGroup, serviceName, targetSegment, mongodbImage, dbSize)
        deployExporter(serviceGroup, serviceName, targetSegment)
    }

    // deploy mongo-gui & delete mongo-express-resources if they still exist for OSOT
    if (targetSegment == TargetSegment.tst) {
        if (si_openshift.getResourcesByLabel("dc", "branch", BRANCH_NAME).contains("mongo-express")) {
            deleteExpressResources(targetSegment)
        }
        deployGui(serviceGroup, serviceName, targetSegment)
    }
}

void backup(String serviceGroup, String serviceName, TargetSegment targetSegment, String dbSize, String jobName="mongodb-backup-selfservice") {
    fillStaticParameters(serviceGroup, serviceName, targetSegment)
    si_openshift.login(serviceGroup, serviceName, targetSegment)
    
    backupForDeploymentFlag = false
    // if the job is deployed for restore, set backupForDeploymentFlag to true
    if (jobName.equals("mongodb-backup-before-selfservice-restore")) {
        backupForDeploymentFlag = true
    }

    // delete old backup job first
    si_openshift.deleteResourceByName("job", "${BRANCH_NAME}-${jobName}")

    deployBackupPV(serviceGroup, serviceName, targetSegment, true, dbSize)
    deployBackupForDeploymentJob(serviceGroup, serviceName, targetSegment, jobName, backupForDeploymentFlag, dbSize)

}

void restore(String serviceGroup, String serviceName, TargetSegment targetSegment, String timestamp) {
    String backupJobName = "mongodb-backup-before-selfservice-restore"
    
    // default DBSize is needed for memory and cpu requests/limits
    String dbSize = "M"

    backup(serviceGroup, serviceName, targetSegment, dbSize, backupJobName)
    waitForJobCompletion(backupJobName)

    // delete old restore job first
    si_openshift.deleteResourceByName("job", "${BRANCH_NAME}-mongodb-selfservice-restore")
    
    restoreJobTemp = loadFileToWorkspace("mongodb-restore.yaml")
    hosts = getMongoDBHosts()

    def templateParameters = [
        "BRANCH_NAME":          "${BRANCH_NAME}",
        "RESTORE_HOST":         "rs0/${hosts[0]}:27017,${hosts[1]}:27017,${hosts[2]}:27017",
        "RESTORE_IMAGE":        "${RESTORE_IMAGE}",
        "MEMORY_REQUEST":       "${getInternalClusterParametersSizes(targetSegment, dbSize).memoryRequest}",
        "MEMORY_LIMIT":         "${getInternalClusterParametersSizes(targetSegment, dbSize).memoryLimit}",
        "CPU_REQUEST":          "${getInternalClusterParametersSizes(targetSegment, dbSize).cpuRequest}",
        "CPU_LIMIT":            "${getInternalClusterParametersSizes(targetSegment, dbSize).cpuLimit}",
        "MONGODB_SECRETS_NAME": "${MONGODB_SECRETS_NAME}",
        "BACKUP_TIMESTAMP":     "${timestamp}",
        "RESTORE_SELFSERVICE":  "true"
    ]
    si_openshift.processTemplate(restoreJobTemp, templateParameters)
}

private void fillStaticParameters(String serviceGroup, String serviceName, TargetSegment targetSegment) {
    SERVICE_GROUP_MONGOADMIN  = "dbops"
    MONGODB_NAME              = "mongodb"
    BRANCH_NAME               = si_openshift.filterBranchName(env.BRANCH_NAME)
    SERVICE_NAMESPACE         = si_openshift.getServiceNamespace(serviceGroup, serviceName, targetSegment)
    MONGODB_SECRETS_NAME      = "${BRANCH_NAME}-mongodb"
    NO_DB_VERSION             = "noDBVersion"
    FOR_REDEPLOYMENT_SUFFIX   = "-rd"

    RESTORE_IMAGE             = publishImageToInternalRegistry(serviceGroup, serviceName, targetSegment, "prod.docker.system.local/baseimages/", NO_DB_VERSION, "restore", "15")
    EXPORTER_IMAGE            = publishImageToInternalRegistry(serviceGroup, serviceName, targetSegment, "prod.docker.system.local/sda/", NO_DB_VERSION, "exporter", "8")
    BACKUP_IMAGE              = publishImageToInternalRegistry(serviceGroup, serviceName, targetSegment, "prod.docker.system.local/baseimages/", NO_DB_VERSION, "backup", "15")
    GUI_IMAGE                 = publishImageToInternalRegistry(serviceGroup, serviceName, targetSegment, "prod.docker.system.local/sda/", NO_DB_VERSION, "gui", "5")
}

private void performRollingUpgrade(String serviceGroup, String serviceName, TargetSegment targetSegment, String mongodbImage, String dbSize) {
    // delete old redeployment-resources
    deleteOldRedeploymentResources(targetSegment)
    
    // deploy new redeployment-resources
    String jobName = "mongodb-backup-for-deployment"
    deployBackupPV(serviceGroup, serviceName, targetSegment, true, dbSize)
    deployBackupForDeploymentJob(serviceGroup, serviceName, targetSegment, jobName, true, dbSize)

    waitForJobCompletion("backup-for-deployment")
    
    // delete old mongodb-resources
    deleteOldResources(serviceGroup, serviceName, targetSegment, dbSize, false)
    deployReplicaSet(serviceGroup, serviceName, targetSegment, mongodbImage, dbSize)
    deployRestoreJob(serviceGroup, serviceName, targetSegment, dbSize)
    deployExporter(serviceGroup, serviceName, targetSegment)
}

private void deployBackupForDeploymentJob(String serviceGroup, String serviceName, TargetSegment targetSegment, String jobName, boolean backupForDeploymentFlag, String dbSize) {
    def backupHosts = getMongoDBHosts()
    def backupTemp = loadFileToWorkspace("mongodb-backup-job.yaml")
    def templateParameters = [
        "BRANCH_NAME":                "${BRANCH_NAME}",
        "JOB_NAME":                   "${jobName}",
        "BACKUP_HOST":                "rs0/${backupHosts[0]},${backupHosts[1]},${backupHosts[2]}",
        "MONGODB_SECRETS_NAME":       "${MONGODB_SECRETS_NAME}",
        "BACKUP_IMAGE":               "${BACKUP_IMAGE}",
        "MONGO_BACKUP_VOLUME":        "${BRANCH_NAME}-mongobackup",
        "BACKUP_FOR_DEPLOYMENT_FLAG": "${backupForDeploymentFlag}",
        "MEMORY_REQUEST":             "${getInternalClusterParametersSizes(targetSegment, dbSize).memoryRequest}",
        "MEMORY_LIMIT":               "${getInternalClusterParametersSizes(targetSegment, dbSize).memoryLimit}",
        "CPU_REQUEST":                "${getInternalClusterParametersSizes(targetSegment, dbSize).cpuRequest}",
        "CPU_LIMIT":                  "${getInternalClusterParametersSizes(targetSegment, dbSize).cpuLimit}",
        "TARGET_SEGMENT":             "${targetSegment}"
    ]
    si_openshift.login(serviceGroup, serviceName, targetSegment)
    si_openshift.processTemplate(backupTemp, templateParameters)
}

private void deleteOldRedeploymentResources(TargetSegment targetSegment) {
    si_openshift.deleteResourceByName("job", "${BRANCH_NAME}-mongodb-backup-for-deployment")
    si_openshift.deleteResourceByName("pvc", "${BRANCH_NAME}-mongobackup${FOR_REDEPLOYMENT_SUFFIX}")
}

public void deleteDB(String serviceGroup, String serviceName, TargetSegment targetSegment, String dbSize) {
    deleteOldResources(serviceGroup, serviceName, targetSegment, dbSize, true);
}

private void deleteOldResources(String serviceGroup, String serviceName, TargetSegment targetSegment, String dbSize, boolean fullDB) {

    if (fullDB) {
        // filling in branch name
        fillStaticParameters(serviceGroup, serviceName, targetSegment)

        // backup and waiting for completion
        if (!isDeploymentForFeatureBranch(targetSegment)) {
            String backupJobName = "mongodb-backup-before-selfservice-deletedb"
            backup(serviceGroup, serviceName, targetSegment, dbSize, backupJobName)
            waitForJobCompletion(backupJobName)
        }

        // scaling down statefulset
        si_openshift.scaleStatefulsetDown(serviceGroup, serviceName, targetSegment, "${BRANCH_NAME}-mongodb")

        // deleting remaining resources
        for(int i = 1; i <= 5; i++) {
            si_openshift.deleteResourceByName("replicationController", "${BRANCH_NAME}-backend-${i}")
            si_openshift.deleteResourceByName("replicationController", "${BRANCH_NAME}-mongodb-exporter-${i}")
            si_openshift.deleteResourceByName("replicationController", "${BRANCH_NAME}-mongodb-gui-${i}")
        }
        si_openshift.deleteResourceByName("dc", "${BRANCH_NAME}-backend")
        si_openshift.deleteResourceByName("route", "${BRANCH_NAME}-backend-admin-tls")
        si_openshift.deleteResourceByName("route", "${BRANCH_NAME}-backend-tls")
    }

    si_openshift.deleteResourceByName("sts", "${BRANCH_NAME}-mongodb")
    si_openshift.deleteResourceByName("dc", "${BRANCH_NAME}-mongodb-exporter")
    si_openshift.deleteResourceByName("service", "${BRANCH_NAME}-mongodb")
    si_openshift.deleteResourceByName("service", "${BRANCH_NAME}-mongodb-exporter-internal")
    si_openshift.deleteResourceByName("service", "${BRANCH_NAME}-mongodb-internal")
    si_openshift.deleteResourceByName("pvc", "mongodb-${BRANCH_NAME}-mongodb-data-${BRANCH_NAME}-mongodb-0")
    si_openshift.deleteResourceByName("configmap", "${BRANCH_NAME}-mongodb")
    if (!isDeploymentForFeatureBranch(targetSegment)) {
        si_openshift.deleteResourceByName("cronjob", "${BRANCH_NAME}-mongodb-backup")
        si_openshift.deleteResourceByName("pvc", "mongodb-${BRANCH_NAME}-mongodb-data-${BRANCH_NAME}-mongodb-1")
        si_openshift.deleteResourceByName("pvc", "mongodb-${BRANCH_NAME}-mongodb-data-${BRANCH_NAME}-mongodb-2")
        si_openshift.deleteResourceByName("pvc", "${BRANCH_NAME}-mongobackup")
        si_openshift.deleteResourceByName("job", "${BRANCH_NAME}-mongodb-restore")
    }
    si_openshift.deleteResourceByName("secret", "${BRANCH_NAME}-mongodb")
    si_openshift.deleteResourceByName("secret", "${BRANCH_NAME}-mongodb-exporter")
    if (targetSegment == TargetSegment.tst) {
        si_openshift.deleteResourceByName("dc", "${BRANCH_NAME}-mongo-gui")
        si_openshift.deleteResourceByName("service", "${BRANCH_NAME}-mongo-gui-internal")
        si_openshift.deleteResourceByName("route", "${BRANCH_NAME}-mongo-gui")
    }
}

private void deleteExpressResources(TargetSegment targetSegment) {
    si_openshift.deleteResourceByName("dc", "${BRANCH_NAME}-mongo-express")
    si_openshift.deleteResourceByName("service", "${BRANCH_NAME}-mongo-express-internal")
    si_openshift.deleteResourceByName("route", "${BRANCH_NAME}-mongo-express")
}

private void deployReplicaSet(String serviceGroup, String serviceName, TargetSegment targetSegment, String mongodbImage, String dbSize) {
    createConfig(serviceGroup, serviceName, targetSegment)
    createSecret(serviceGroup, serviceName, targetSegment)
    deploy(serviceGroup, serviceName, targetSegment, mongodbImage, dbSize)
    deployBackupPV(serviceGroup, serviceName, targetSegment, false, dbSize)
    deployBackup(serviceGroup, serviceName, targetSegment, BACKUP_SCHEDULE, dbSize)
}

private void createConfig(String serviceGroup, String serviceName, TargetSegment targetSegment) {
    def secretTemp = loadFileToWorkspace("mongodb-replicaset-persistent-config.yaml")
    def templateParameters = [
            "BRANCH_NAME": "${BRANCH_NAME}",
            "SERVICE_NAMESPACE": "${SERVICE_NAMESPACE}"
    ]
    si_openshift.login(serviceGroup, serviceName, targetSegment)
    si_openshift.processTemplate(secretTemp, templateParameters)
}

/**
 * create secret to use in replicaset and backup
 */
private void createSecret(String serviceGroup, String serviceName, TargetSegment targetSegment) {
    withCredentials([
            [$class: 'UsernamePasswordMultiBinding', credentialsId: "${SERVICE_GROUP_MONGOADMIN}-secret-${targetSegment}-mongoadmin", usernameVariable: 'MONGODB_ADMIN_USER', passwordVariable: 'MONGODB_ADMIN_PASSWORD'],
            [$class: 'UsernamePasswordMultiBinding', credentialsId: "${SERVICE_GROUP_MONGOADMIN}-secret-${targetSegment}-mongouser", usernameVariable: 'MONGODB_USER', passwordVariable: 'MONGODB_PASSWORD'],
            [$class: 'StringBinding', credentialsId: "${SERVICE_GROUP_MONGOADMIN}-secret-${targetSegment}-mongokeyfile", variable: 'MONGODB_KEYFILE_VALUE']
    ]){
        secretTemp = loadFileToWorkspace("mongodb-replicaset-persistent-secret.yaml")
        def templateParameters = [
                "BRANCH_NAME": "${BRANCH_NAME}",
                "MONGODB_SECRETS_NAME": "${MONGODB_SECRETS_NAME}",
                "MONGODB_KEYFILE_VALUE": "${MONGODB_KEYFILE_VALUE}",
                "MONGODB_ADMIN_USER": "${MONGODB_ADMIN_USER}",
                "MONGODB_ADMIN_PASSWORD": "${MONGODB_ADMIN_PASSWORD}",
                "MONGODB_USER": "${MONGODB_USER}",
                "MONGODB_PASSWORD": "${MONGODB_PASSWORD}"
        ]
        si_openshift.login(serviceGroup, serviceName, targetSegment)
        si_openshift.processTemplate(secretTemp, templateParameters)
    }
}

/**
 * deploy the mongodb replicaset as statefulset
 */
private void deploy(String serviceGroup, String serviceName, TargetSegment targetSegment, String mongodbImage, String dbSize) {
    if (isDeploymentForFeatureBranch(targetSegment)) {
        replicasetSize = 1
    } else { // Deployment is for ABN, PRD or Develop-Branch in TST
        replicasetSize = 3
    }

    def thirdRZ
    if (targetSegment == TargetSegment.prd) {
        thirdRZ = "RZ3"
    } else {
        thirdRZ = "VMware"
    }

    resourceName = buildresourceName("mongodb")
    persistentTemp = loadFileToWorkspace("mongodb-replicaset-persistent.yaml")
    def templateParameters = [
            "BRANCH_NAME":           "${BRANCH_NAME}",
            "TARGET_SEGMENT":        "${targetSegment}",
            "MONGODB_SECRETS_NAME":  "${MONGODB_SECRETS_NAME}",
            "MONGODB_DATABASE":      "${MONGODB_NAME}",
            "MONGODB_SERVICE_NAME":  "${resourceName}",
            "MONGODB_IMAGE":         "${mongodbImage}",
            "REPLICASET_SIZE":       "${replicasetSize}",
            "VOLUME_CAPACITY":       "${getInternalClusterParametersSizes(targetSegment, dbSize).volumeCapacity}",
            "MEMORY_REQUEST":        "${getInternalClusterParametersSizes(targetSegment, dbSize).memoryRequest}",
            "MEMORY_LIMIT":          "${getInternalClusterParametersSizes(targetSegment, dbSize).memoryLimit}",
            "CPU_REQUEST":           "${getInternalClusterParametersSizes(targetSegment, dbSize).cpuRequest}",
            "CPU_LIMIT":             "${getInternalClusterParametersSizes(targetSegment, dbSize).cpuLimit}",
            "WIREDTIGER_CACHE_SIZE": "${getInternalClusterParametersSizes(targetSegment, dbSize).wiredtigerCacheSize}",
            "MONGODB_STORAGE_CLASS": "${getMongoDBStorageClass(targetSegment)}",
            "THIRD_RZ":              "${thirdRZ}"
    ]
    si_openshift.login(serviceGroup, serviceName, targetSegment)
    si_openshift.processTemplate(persistentTemp, templateParameters)
    si_openshift.checkStatefulsetRollout(serviceGroup, serviceName, targetSegment, resourceName)
}

/**
 * create persistent volume for backup-cronjob in PRD and ABN
 */
private void deployBackupPV(String serviceGroup, String serviceName, TargetSegment targetSegment, boolean forRedeployment, String dbSize) {
    if (!isDeploymentForFeatureBranch(targetSegment)) {
        backupvolTemp = loadFileToWorkspace("mongodb-backup-volume.yaml")
        def templateParameters
        if (forRedeployment) {
            templateParameters = [
                "BRANCH_NAME": "${BRANCH_NAME}",
                "STORAGE_SIZE": "600Gi",
                "FOR_REDEPLOYMENT_SUFFIX": "${FOR_REDEPLOYMENT_SUFFIX}"
            ]
        } else {
            storageSize = getInternalClusterParametersSizes(targetSegment, dbSize).backupStorageSize
            templateParameters = [
                "BRANCH_NAME": "${BRANCH_NAME}",
                "STORAGE_SIZE": getInternalClusterParametersSizes(targetSegment, dbSize).backupStorageSize,
                "FOR_REDEPLOYMENT_SUFFIX": ""
            ]
        }
        si_openshift.login(serviceGroup, serviceName, targetSegment)
        si_openshift.processTemplate(backupvolTemp, templateParameters)
    }
}

/**
 * deploy the backup-cronjob
 */
private void deployBackup(String serviceGroup, String serviceName, TargetSegment targetSegment, String backupSchedule, String dbSize) {
    if (!isDeploymentForFeatureBranch(targetSegment)) {
        backupTemp = loadFileToWorkspace("mongodb-backup-cronjob.yaml")
        def hosts = getMongoDBHosts()
        def templateParameters = [
            "BRANCH_NAME":          "${BRANCH_NAME}",
            "MONGODB_SECRETS_NAME": "${MONGODB_SECRETS_NAME}",
            "BACKUP_HOST":          "rs0/${hosts[0]},${hosts[1]},${hosts[2]}",
            "BACKUP_SCHEDULE":      "\"${backupSchedule}\"",
            "BACKUP_IMAGE":         "${BACKUP_IMAGE}",
            "MONGODB_BACKUP_KEEP":  "${MONGODB_BACKUP_KEEP}",
            "VOLUME_CAPACITY":      "${getInternalClusterParametersSizes(targetSegment, dbSize).volumeCapacity}",
            "MEMORY_REQUEST":       "${getInternalClusterParametersSizes(targetSegment, dbSize).memoryRequest}",
            "MEMORY_LIMIT":         "${getInternalClusterParametersSizes(targetSegment, dbSize).memoryLimit}",
            "CPU_REQUEST":          "${getInternalClusterParametersSizes(targetSegment, dbSize).cpuRequest}",
            "CPU_LIMIT":            "${getInternalClusterParametersSizes(targetSegment, dbSize).cpuLimit}",
            "TARGET_SEGMENT":       "${targetSegment}"
        ]
        si_openshift.login(serviceGroup, serviceName, targetSegment)
        si_openshift.processTemplate(backupTemp, templateParameters)
    }
}

/**
 * deploy dbexporter as statefulset to get mongodb metrics
 */
private void deployExporter(String serviceGroup, String serviceName, TargetSegment targetSegment) {
    withCredentials([usernamePassword(credentialsId: "${SERVICE_GROUP_MONGOADMIN}-secret-${targetSegment}-mongoadmin", usernameVariable: 'MONGODB_ADMIN_USER', passwordVariable: 'MONGODB_ADMIN_PASSWORD')]) {
        resourceNameExporter =  buildresourceName("mongodb")
        resourceNameRollout = buildresourceName("mongodb-exporter")
        exporterTemp = loadFileToWorkspace("mongodb-exporter.yaml")
        def templateParameters = [
                "BRANCH_NAME":"${BRANCH_NAME}",
                "RESOURCE_NAME_PREFIX": "${resourceNameExporter}",
                "MONGODB_ADMIN_PASSWORD": "${MONGODB_ADMIN_PASSWORD}",
                "SERVICE_NAMESPACE": "${SERVICE_NAMESPACE}",
                "EXPORTER_IMAGE": "${EXPORTER_IMAGE}"
        ]
        si_openshift.login(serviceGroup, serviceName, targetSegment)
        si_openshift.processTemplate(exporterTemp, templateParameters)
        si_openshift.deployContainer(serviceGroup, serviceName, targetSegment, resourceNameRollout)
    }
}

private void deployRestoreJob(String serviceGroup, String serviceName, TargetSegment targetSegment, String dbSize) {
    restoreJobTemp = loadFileToWorkspace("mongodb-deployment-restore.yaml")
    hosts = getMongoDBHosts()
    def templateParameters = [
        "BRANCH_NAME":          "${BRANCH_NAME}",
        "RESTORE_HOST":         "rs0/${hosts[0]}:27017,${hosts[1]}:27017,${hosts[2]}:27017",
        "BACKUP_PATH":          "/var/lib/mongodb/redeployment",
        "RESTORE_IMAGE":        "${RESTORE_IMAGE}",
        "MEMORY_REQUEST":       "${getInternalClusterParametersSizes(targetSegment, dbSize).memoryRequest}",
        "MEMORY_LIMIT":         "${getInternalClusterParametersSizes(targetSegment, dbSize).memoryLimit}",
        "CPU_REQUEST":          "${getInternalClusterParametersSizes(targetSegment, dbSize).cpuRequest}",
        "CPU_LIMIT":            "${getInternalClusterParametersSizes(targetSegment, dbSize).cpuLimit}",
        "MONGODB_SECRETS_NAME": "${MONGODB_SECRETS_NAME}",
        "FOR_DEPLOYMENT":       "${FOR_REDEPLOYMENT_SUFFIX}",
        "RESTORE_SELFSERVICE":  "false"
    ]
    si_openshift.login(serviceGroup, serviceName, targetSegment)
    si_openshift.processTemplate(restoreJobTemp, templateParameters)
}

/**
 * deploy mongo-gui (Web-GUI)
 */ 
private void deployGui(String serviceGroup, String serviceName, TargetSegment targetSegment) {
    withCredentials([usernamePassword(credentialsId: "${SERVICE_GROUP_MONGOADMIN}-secret-${targetSegment}-mongoadmin", usernameVariable: 'MONGODB_ADMIN_USER', passwordVariable: 'MONGODB_ADMIN_PASSWORD')]) {
        resourceNameRollout = buildresourceName("mongo-gui")
        guiTemp = loadFileToWorkspace("mongo-gui.yaml")
        def shorterServiceNamespace = serviceGroup.take(18) + "-" + serviceName.take(18)
        def templateParameters = [
            "BRANCH_NAME": "${BRANCH_NAME}",
            "MONGODB_SECRETS_NAME": "${MONGODB_SECRETS_NAME}",
            "MONGODB_ADMIN_PASSWORD": "${MONGODB_ADMIN_PASSWORD}",
            "SERVICE_NAMESPACE": "${SERVICE_NAMESPACE}",
            "SERVICE_NAMESPACE_FOR_ROUTE": "${shorterServiceNamespace}",
            "GUI_IMAGE": "${GUI_IMAGE}"
        ]
        si_openshift.login(serviceGroup, serviceName, targetSegment)
        si_openshift.processTemplate(guiTemp, templateParameters)
        si_openshift.deployContainer(serviceGroup, serviceName, targetSegment, resourceNameRollout)
    }
}

private String publishImageToInternalRegistry(String serviceGroup, String serviceName, TargetSegment targetSegment, String registry, String mongodbVersion, String imageSuffix, String imageTag) {
    String version = ""
    if (!mongodbVersion.equals(NO_DB_VERSION)) {
        version = getMongodbVersion(mongodbVersion)
    }
    String imageName = "mongodb${version}-${imageSuffix}"
    String imagePath = "${registry}${imageName}:${imageTag}"
    si_docker.publishImageWithExistenceCheck(imagePath, imageName, imageTag, serviceGroup, serviceName, targetSegment)
    return si_docker.getInternalImagePathByNameAndTag(serviceGroup, serviceName, targetSegment, imageName, imageTag)
}

private Map getInternalClusterParametersSizes(TargetSegment targetSegment, String dbSize) {
        
    def tstParams = [
        volumeCapacity: "10Gi",
        memoryRequest: "500Mi",
        memoryLimit: "2000Mi",
        cpuRequest: "100m",
        cpuLimit: "2000m",
        wiredtigerCacheSize: "256M"
    ]

    // sizes for DB_SIZE = S
    def sParams = [
        volumeCapacity: "50Gi",
        memoryRequest: "500Mi",
        memoryLimit: "2000Mi",
        cpuRequest: "100m",
        cpuLimit: "2000m",
        wiredtigerCacheSize: "256M",
        backupStorageSize: "150Gi"
    ]

    // sizes for DB_SIZE = M
    def mParams = [
        volumeCapacity: "100Gi",
        memoryRequest: "500Mi",
        memoryLimit: "2000Mi",
        cpuRequest: "100m",
        cpuLimit: "2000m",
        wiredtigerCacheSize: "256M",
        backupStorageSize: "300Gi"
    ]
    
    // sizes for DB_SIZE = L
    def lParams = [
        volumeCapacity: "200Gi",
        memoryRequest: "500Mi",
        memoryLimit: "2000Mi",
        cpuRequest: "100m",
        cpuLimit: "2000m",
        wiredtigerCacheSize: "1024M",
        backupStorageSize: "600Gi"
    ]

    // sizes for DB_SIZE = XL
    def xlParams = [
        volumeCapacity: "200Gi",
        memoryRequest: "500Mi",
        memoryLimit: "8000Mi",
        cpuRequest: "100m",
        cpuLimit: "4000m",
        wiredtigerCacheSize: "3072M",
        backupStorageSize: "600Gi"
    ]

    // sizes for DB_SIZE = ODS
    def odsParams = [
        volumeCapacity: "200Gi",
        memoryRequest: "500Mi",
        memoryLimit: "16000Mi",
        cpuRequest: "100m",
        cpuLimit: "4000m",
        wiredtigerCacheSize: "7680M",
        backupStorageSize: "600Gi"
    ]

     // sizes for DB_SIZE = CLI
    def cliParams = [
        volumeCapacity: "600Gi",
        memoryRequest: "500Mi",
        memoryLimit: "2000Mi",
        cpuRequest: "100m",
        cpuLimit: "2000m",
        wiredtigerCacheSize: "256M",
        backupStorageSize: "600Gi"
    ]

    // sizes for DB_SIZE = ICODS
    def icodsParams = [
        volumeCapacity: "200Gi",
        memoryRequest: "500Mi",
        memoryLimit: "32000Mi",
        cpuRequest: "100m",
        cpuLimit: "4000m",
        wiredtigerCacheSize: "7680M",
        backupStorageSize: "600Gi"
    ] 

    // deploy mini-databases in feature-Branches
    if (isDeploymentForFeatureBranch(targetSegment)) {
        result = tstParams
    } else if (targetSegment != TargetSegment.prd && (dbSize == "ODS" || dbSize == "ICODS")) {
        result = xlParams
    } else {
        switch(dbSize){
            case "S": result = sParams; break;
            case "L": result = lParams; break;
            case "XL": result = xlParams; break;
            case "ODS": result = odsParams; break;
            case "CLI": result = cliParams; break;
            case "ICODS": result = icodsParams; break;
            default: result = mParams; break;
        }
    }
    return result
}

private String buildresourceName(String objectName) {
    return BRANCH_NAME + '-' + objectName
}

private void waitForJobCompletion(String jobName) {
    def podName = ""
    def podInfo
    def podLogs = ""
    def counter = 0

    while (podInfo == null) {
        println("Pod not created yet")
        sleep(5)
        podInfo = si_openshift.getResourcesByPattern("pod", jobName)
        counter += 1
        // if the waiting time is greater or equal 3 minutes, stop the build
        if (counter >= 36) {
            throw new RuntimeException("Pod could not be created")
        }
    }

    counter = 0
    // while state "ContainerCreating" and "Pending" asking for Logs results in an error. So first waiting until Container is created...
    while (podInfo.contains("ContainerCreating") || podInfo.contains("Pending")) {
        println("Waiting for container to be created.")
        sleep(5)
        podInfo = si_openshift.getResourcesByPattern("pod", jobName)
        counter += 1
        // if the waiting time is greater or equal 3 minutes, stop the build
        if (counter >= 36) {
            throw new RuntimeException("Pod was too long in state 'ContainerCreating' or 'Pending'")
        }
    }
    
    counter = 0
    // wait until job is completed
    while (!podInfo.contains("Completed")) {
        println("Check status of job every 20 seconds.")
        sleep(20)
        podInfo = si_openshift.getResourcesByPattern("pod", jobName)
        counter += 1
        // if the waiting time is greater or equal 2 hours, stop the build
        if (counter >= 360) {
            throw new RuntimeException("Job was running too long")
        }
    }
    podName = podInfo.split(" ")[0]
    podLogs = si_openshift.getPodLogs(podName)
    // throw Exception if the Pod is in state "Failed" or the Logs show Errors
    if (podInfo.contains("Failed") || podLogs.contains("Failed")) {
        throw new RuntimeException("Job has failures in Logs")
    }
}

private String getMongodbVersion(String mongodbVersion) {
    if(mongodbVersion.equals("4.4")) {
		return "44"
    } else if(mongodbVersion.equals("5.0")) {
        return "50"
    } else if(mongodbVersion.equals("6.0")) {
        return "60"
	} else {
        throw new IllegalArgumentException("Parameter mongodb_version ${mongodbVersion} is not valid. MongoDB ${mongodbVersion} will not be deployed.")
	}
}

/**
 * copy content of yaml file to local workspace
 */
private String loadFileToWorkspace(String fileName) {
    def folder = "de/signaliduna"
    def filePath = "${folder}/${fileName}"
    def fileExtension = fileName.split("\\.")[1]
    def tempFile = "tempFile.${fileExtension}"
    fileContent =  libraryResource "${filePath}"
    writeFile file:"${env.workspace}/${tempFile}", text:"${fileContent}"
    return tempFile
}

private List<String> getMongoDBHosts() {
    def resourceNameMongo = buildresourceName("mongodb")
    def hosts = [
        "${resourceNameMongo}-0.${resourceNameMongo}-internal.${SERVICE_NAMESPACE}.svc.cluster.local",
        "${resourceNameMongo}-1.${resourceNameMongo}-internal.${SERVICE_NAMESPACE}.svc.cluster.local",
        "${resourceNameMongo}-2.${resourceNameMongo}-internal.${SERVICE_NAMESPACE}.svc.cluster.local"
    ]
    return hosts
}

/**
 * check and set the new mongodbBackupKeepNew parameter
 */
private int checkMongodbBackupKeep(int mongodbBackupKeepNew) {
	if (mongodbBackupKeepNew < 3|| mongodbBackupKeepNew > 14) {
		throw new IllegalArgumentException("Parameter mongodbBackupKeep=${mongodbBackupKeepNew} is not valid. Backups with mongodbBackupKeep=${mongodbBackupKeepNew}  will not be deployed. Please set mongodbBackupKeep between 3 and 14! ")	
	} else {
		return mongodbBackupKeepNew            
	}
}

/*
* check and set the new backupSchudule
*/
private String checkBackupSchedule(String backupScheduleNew) {

    String[] backupSchedule_splitted = backupScheduleNew.split("[\\s]")

    final int MIN = 0, HOUR = 1, DAY_OF_MONTH = 2, MONTH = 3, DAY_OF_WEEK = 4

    //PATTERN_MIN
    final String PATTERN_MIN_DIGITS = "([*]|(0*[0-5]?[0-9]))" // * or Digits from 0 to 59
    final String PATTERN_MIN_OUTSOURCED_PIECE = "((" + PATTERN_MIN_DIGITS + "[,])*" + PATTERN_MIN_DIGITS + "(([/]|[-])" + PATTERN_MIN_DIGITS + ")?)+"

    //PATTERN HOUR
    final String PATTERN_HOUR_DIGITS = "([*]|(0*[0-9]|1[0-9]|2[0-3]))" // * or Digits from 0 to 23
    final String PATTERN_HOUR_OUTSOURCED_PIECE = "((" + PATTERN_HOUR_DIGITS + "[,])*" + PATTERN_HOUR_DIGITS + "(([/]|[-])" + PATTERN_HOUR_DIGITS + ")?)+"

    //PATTERN DAY OF MONTH
    final String PATTERN_DAY_OF_MONTH_DIGITS = "([*]|(0*[1-9]|1[0-9]|2[0-9]|3[0-1]))" // * or Digits from 0 to 31
    final String PATTERN_DAY_OF_MONTH_OUTSOURCED_PIECE = "((" + PATTERN_DAY_OF_MONTH_DIGITS + "[,])*" + PATTERN_DAY_OF_MONTH_DIGITS + "(([/]|[-])" + PATTERN_DAY_OF_MONTH_DIGITS + ")?)+"

    //PATTERN MONTH
    final String PATTERN_MONTH_DIGITS = "([*]|(0*[1-9]|1[0-2]))" // * or Digits from 1 to 12

    final String PATTERN_MONTH_CHARACTER =  "(([j]|[J])([a]|[A])([n]|[N]))|" + //January
                                            "(([f]|[F])([e]|[E])([b]|[B]))|" + //February
                                            "(([m]|[M])([a]|[A])([r]|[R]))|" + //March
                                            "(([a]|[A])([p]|[P])([r]|[R]))|" + //April
                                            "(([m]|[M])([a]|[A])([y]|[Y]))|" + //May
                                            "(([j]|[J])([u]|[U])([n]|[N]))|" + //June
                                            "(([j]|[J])([u]|[U])([l]|[L]))|" + //July
                                            "(([a]|[A])([u]|[U])([g]|[G]))|" + //August
                                            "(([s]|[S])([e]|[E])([p]|[P]))|" + //September
                                            "(([o]|[O])([c]|[C])([t]|[T]))|" + //October
                                            "(([n]|[N])([o]|[O])([v]|[V]))|" + //November
                                            "(([d]|[D])([e]|[E])([c]|[C]))"    //December

    final String PATTERN_MONTH_DIGITS_AND_CHARACTER = "(" + PATTERN_MONTH_DIGITS + "|" + PATTERN_MONTH_CHARACTER + ")"

    final String PATTERN_MONTH_OUTSOURCED_PIECE = "((" + PATTERN_MONTH_DIGITS_AND_CHARACTER + "[,])*" + PATTERN_MONTH_DIGITS_AND_CHARACTER + "(([/]|[-])" + PATTERN_MONTH_DIGITS_AND_CHARACTER + ")?)+"

    //PATTERN DAY OF WEEK
    final String PATTERN_DAY_OF_WEEK_DIGITS = "([*]|(0*[0-7]))" // * or Digits from 0 to 7

    final String PATTERN_DAY_OF_WEEK_CHARACTER = "(([m]|[M])([o]|[O])([n]|[N]))|" + //Monday
                                                 "(([t]|[T])([u]|[U])([e]|[E]))|" + //Tuesday
                                                 "(([w]|[W])([e]|[E])([d]|[D]))|" + //Wednesday
                                                 "(([t]|[T])([h]|[H])([u]|[U]))|" + //Thursday
                                                 "(([f]|[F])([r]|[R])([i]|[I]))|" + //Friday
                                                 "(([s]|[S])([a]|[A])([t]|[T]))|" + //Saturday
                                                 "(([s]|[S])([u]|[U])([n]|[N]))"    //Sunday

    final String PATTERN_DAY_OF_WEEK_DIGITS_AND_CHARACTER = "(" + PATTERN_DAY_OF_WEEK_DIGITS + "|" + PATTERN_DAY_OF_WEEK_CHARACTER + ")"

    final String PATTERN_DAY_OF_WEEK_OUTSOURCED_PIECE = "((" + PATTERN_DAY_OF_WEEK_DIGITS_AND_CHARACTER + "[,])*" + PATTERN_DAY_OF_WEEK_DIGITS_AND_CHARACTER + "(([/]|[-])" + PATTERN_DAY_OF_WEEK_DIGITS_AND_CHARACTER + ")?)+"

    //PATTERN
    final Pattern PATTERN_FULL = Pattern.compile("[@yearly]|[@annually]|[@monthly]|[@weekly]|[@daily]|[@hourly]|[@reboot]")
    final Pattern PATTERN_MIN = Pattern.compile(PATTERN_MIN_OUTSOURCED_PIECE + "([,]" + PATTERN_MIN_OUTSOURCED_PIECE + ")*")
    final Pattern PATTERN_HOUR = Pattern.compile(PATTERN_HOUR_OUTSOURCED_PIECE + "([,]" + PATTERN_HOUR_OUTSOURCED_PIECE + ")*")
    final Pattern PATTERN_DAY_OF_MONTH = Pattern.compile(PATTERN_DAY_OF_MONTH_OUTSOURCED_PIECE + "([,]" + PATTERN_DAY_OF_MONTH_OUTSOURCED_PIECE + ")*")
    final Pattern PATTERN_MONTH = Pattern.compile(PATTERN_MONTH_OUTSOURCED_PIECE + "([,]" + PATTERN_MONTH_OUTSOURCED_PIECE + ")*")
    final Pattern PATTERN_DAY_OF_WEEK = Pattern.compile(PATTERN_DAY_OF_WEEK_OUTSOURCED_PIECE + "([,]" + PATTERN_DAY_OF_WEEK_OUTSOURCED_PIECE + ")*")


    if (backupScheduleNew != null && PATTERN_FULL.matcher(backupScheduleNew).matches()) {
        return backupScheduleNew
    } else if(backupSchedule_splitted.length == 5 &&
            PATTERN_MIN.matcher(backupSchedule_splitted[MIN]).matches() &&
            PATTERN_HOUR.matcher(backupSchedule_splitted[HOUR]).matches() &&
            PATTERN_DAY_OF_MONTH.matcher(backupSchedule_splitted[DAY_OF_MONTH]).matches() &&
            PATTERN_MONTH.matcher(backupSchedule_splitted[MONTH]).matches() &&
            PATTERN_DAY_OF_WEEK.matcher(backupSchedule_splitted[DAY_OF_WEEK]).matches()) {
        return backupScheduleNew
    } else {
        throw new IllegalArgumentException("The parameter BACKUP_SCHEDULE=${backupScheduleNew} is not valid, please check in the link https://crontab.guru/ for a right form!")
    }
}

private boolean mongoDBExistsInProject() {
    String stsNames = si_openshift.getResourcesByLabel("sts", "branch", BRANCH_NAME)
    return stsNames.contains("${BRANCH_NAME}-mongodb")
} 

private boolean isDeploymentForFeatureBranch(TargetSegment targetSegment) {
    return (targetSegment == TargetSegment.tst && !BRANCH_NAME.startsWith("develop") && BRANCH_NAME != "master")
}


private boolean isVersion(String version) {
    // this is the selector to only get the Image from the stsInfo.
    def templateSelector = "{{range .spec.template.spec.containers}}{{.image}}{{end}}"

    def stsInfo = si_openshift.getResourceInformation("sts", "${BRANCH_NAME}-mongodb", templateSelector)

    // Example: stsInfo = app416020.system.local:5000/aedbaexp-mongodb-tst/mongodb42-replset:1 or feature-mongodb42-replset
    // Now this String must be splitted by "/" and "-" and "-replset:" to just get "mongodb42"
    def splittedstsInfo = stsInfo.split("/|-|-replset:")
    def image = splittedstsInfo.getAt(splittedstsInfo.size() - 2)
    if (image == "mongodb" + version) {
        return true
    } else {
        return false
    }
}

private boolean isSizeChanged(TargetSegment targetSegment, String dbSize) {
    def templateSelector = "{{.spec.resources.requests.storage}}"
    def pvcInfo = si_openshift.getResourceInformation("pvc", "mongodb-${BRANCH_NAME}-mongodb-data-${BRANCH_NAME}-mongodb-0", templateSelector)
    // remove linebreak from output
    pvcInfo = pvcInfo.replaceAll("[^a-zA-Z0-9 ]+","")
    requestedPVCSize = getInternalClusterParametersSizes(targetSegment, dbSize).volumeCapacity
    requestedPVCSize = requestedPVCSize.replaceAll("[^a-zA-Z0-9 ]+","")
    return !pvcInfo.equals(requestedPVCSize)
}

/**
 * New storage class should only be used for new statefulset to prevent breaking change
 */
private String getMongoDBStorageClass(TargetSegment targetSegment) {
    if (isDeploymentForFeatureBranch(targetSegment)) {
        println("Waiting for resources to be deleted (only Feature-Branches)")
        sleep(20)
    }
    if (storageClassAlreadyDeployed("premium")) {
        return "premium"
    } else if (storageClassAlreadyDeployed("nfs-snapshot")) {
        return "nfs-snapshot"
    } else if (targetSegment == TargetSegment.tst) {
        return "nfs"
    } else {
        return "nfs-snapshot" // Default Storageclass for PRD & ABN
    }
}

private void setFeatureCompatibility(String serviceGroup, String serviceName, TargetSegment targetSegment, String desiredVersion) {
    withCredentials([usernamePassword(credentialsId: "${SERVICE_GROUP_MONGOADMIN}-secret-${targetSegment}-mongoadmin", usernameVariable: 'MONGODB_ADMIN_USER', passwordVariable: 'MONGODB_ADMIN_PASSWORD')]) {
        si_openshift.login(serviceGroup, serviceName, targetSegment)
        String command = "mongosh --host rs0/${BRANCH_NAME}-mongodb-internal.${serviceGroup}-${serviceName}-${targetSegment}.svc --username ${MONGODB_ADMIN_USER} --password ${MONGODB_ADMIN_PASSWORD} --authenticationDatabase admin mongodb --eval 'db.adminCommand( { setFeatureCompatibilityVersion: \"${desiredVersion}\" } )'"
        execCommandInPod("${BRANCH_NAME}-mongodb-0", command)
    }
}

private boolean storageClassAlreadyDeployed(String storageClass) {
    // Die Leerzeichen vor und nach der Storageklasse werden benötigt, falls ein Storageclassname woanders in der Zeile (z. B. Namespace) auftaucht.
    return si_openshift.getResourcesByPattern("pvc", " " + storageClass + " ").contains("mongodb-${BRANCH_NAME}-mongodb-data-${BRANCH_NAME}-mongodb")
}

private String execCommandInPod(String podName, String command) {
    String commandOutput = sh(
        returnStdout: true,
        script: "oc exec ${podName} --config ${getCliContext()} -- ${command}"
    ).trim()
    return commandOutput
}

private static String getCliContext() {
    return "cli-context"
}

void printVersionHint(String usedVersion, String latestVersion) {
    echo "┳┻|\n┻┳|\n┳┻| _     HEADS UP! The MongoDB Version ${usedVersion} in your Jenkinsfile is deprecated.\n┻┳| •.•)\n┳┻|⊂ﾉ     Please use ${latestVersion} now.\n┻┳|\n┳┻|"
}
