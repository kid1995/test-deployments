import java.text.SimpleDateFormat
import groovy.json.JsonSlurper
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import de.signaliduna.TargetSegment
import de.signaliduna.BusinessException
import hudson.FilePath;

@groovy.transform.Field
def ITSM_REST_API = "http://its360.system.local:31190/SM/9/rest"

@groovy.transform.Field
def changeCoordinator = "U006428"

@groovy.transform.Field
def IGNORE_ALL_ITS360_EXCEPTIONS = false

@groovy.transform.Field
def TECHNICAL_EXCEPTION_OCCURED = false

@groovy.transform.Field
def TECHNICAL_EXCEPTION_MESSAGE = ""

@groovy.transform.Field
def NOTIFY_CHANGE_MANAGER_FROM = "change-manager@signal-iduna.de"

@groovy.transform.Field
def NOTIFY_CHANGE_MANAGER_TO = "change-manager@signal-iduna.de,orhan.polat@signal-iduna.de,holger.endres@signal-iduna.de,koray.bayraktar@signal-iduna.de"

@groovy.transform.Field
def DOCUMENTATION_URL = "http://wiki.system.local/x/3vUdCw"

@groovy.transform.Field
def maxRetry = 3

@groovy.transform.Field
def waitBetweenRetryInSeconds = 5

/**
 * Deploy Services and handling change-automation
 */
public notifyReleaseDeployment(String serviceGroup, String serviceName, Closure closure)  throws Exception {
	notifyReleaseDeployment(serviceGroup, serviceName, closure, true, true, "SDA-Service Vorlage", null)
}

/**
 * Deploy Services and handling change-automation
 */
public notifyReleaseDeployment(String serviceGroup, String serviceName, Closure closure, String alternativeSecret)  throws Exception {
	notifyReleaseDeployment(serviceGroup, serviceName, closure, true, true, "SDA-Service Vorlage", null, alternativeSecret)
}

public notifyReleaseDeployment(String serviceGroup, String serviceName, Closure closure, boolean preCheckNormalChangeExists, boolean isOCDeployment, String changeModel, String assignmentGroup)  throws Exception {
    notifyReleaseDeployment(serviceGroup, serviceName, closure, preCheckNormalChangeExists, isOCDeployment, changeModel, assignmentGroup, null)
}
/**
 * Deploy Services and handling change-automation
 */
public notifyReleaseDeployment(String serviceGroup, String serviceName, Closure closure, boolean preCheckNormalChangeExists, boolean isOCDeployment, String changeModel, String assignmentGroup, String alternativeSecret)  throws Exception {

    def implementationStart = OffsetDateTime.now(ZoneOffset.UTC) as String
    // time format example -> "2020-05-10T22:00:00+00:00"
    def requestedEndDate = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30) as String
    def plannedStart = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10) as String
    def plannedEnd = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30) as String
    def ciIdApplicationService
    def isNormalChange
    def changeId

    try {
        if (alternativeSecret == null) {
            ciIdApplicationService = getConfigurationItem(serviceGroup, serviceName)
        } else {
            ciIdApplicationService = getConfigurationItem(alternativeSecret)
        }

      	if (preCheckNormalChangeExists) {
      		isNormalChange = preCheckChanges(ciIdApplicationService)

          if (isNormalChange) {
            changeId = getNormalChangeIdByCiId(ciIdApplicationService)
            checkIfChangeLockedByUser(changeId)
            checkPreConditionsOfNormalChange(changeId)
          } else {
            changeId = getStandardChangeIdByCiId(ciIdApplicationService)

            if (changeId != null) {
                checkPreConditionsOfStandardChange(changeId)
            } else {
                checkTimePeriodConflict(ciIdApplicationService, requestedEndDate, changeCoordinator, plannedStart, plannedEnd, changeModel)
            }
          }
        }
    } catch(Exception e) {
        handleException(e)
    }

    // execute deployment
    try {
        closure.call()

        def implementationEnd = OffsetDateTime.now(ZoneOffset.UTC) as String

        def implementationComments = "Ausführung des Deployments war ERFOLGREICH !\nBuild-ID: ${env.BUILD_ID}\nBuild-URL: ${env.BUILD_URL}"
        if (!isOCDeployment) {
            implementationComments = "Ausführung der Änderung war ERFOLGREICH !\nBuild-ID: ${env.BUILD_ID}\nBuild-URL: ${env.BUILD_URL}"
        }

        try {
            if (isNormalChange) {
                changeToCmdbUpdated(changeId, implementationStart, implementationEnd, implementationComments)
            } else  {
                createStandardChange(serviceGroup, serviceName, ciIdApplicationService, requestedEndDate, implementationStart, implementationEnd, implementationComments, plannedStart, plannedEnd, changeId, isOCDeployment, changeModel, assignmentGroup)
            }
        } catch (Exception e) {
            handleException(e)
        }

    } catch (Exception e) {
        try {
            if (isNormalChange) {
                updateImplementationComments(changeId, String.valueOf("Ausführung des Deployments ist FEHLERHAFT !\nBuild-ID: ${env.BUILD_ID}\nBuild-URL: ${env.BUILD_URL}"))
            } else {
                implementationEnd = OffsetDateTime.now(ZoneOffset.UTC) as String
                def implementationComments = "Build-ID: ${env.BUILD_ID}\nBuild-URL: ${env.BUILD_URL}"
                def fallbackComment = getErrorStacktraceAsText(e)
                createStandardChangeForFallBack(serviceGroup, serviceName, ciIdApplicationService, requestedEndDate, implementationStart, implementationEnd, implementationComments, plannedStart, plannedEnd, fallbackComment, changeId, isOCDeployment, changeModel, assignmentGroup)
            }
        } catch (Exception exp) {
            handleException(exp)
        }
        throw e
    }

    if (TECHNICAL_EXCEPTION_OCCURED) {
        notifyChangeManager(changeId, serviceGroup, serviceName)
    }
}

/**
 * Creates a standard change for happy path, deployment of service was successfull.
 */
private createStandardChange(String serviceGroup, String serviceName, String ciIdApplicationService, String requestedEndDate, String implementationStart, String implementationEnd, String implementationComments, String plannedStart, String plannedEnd, String changeId, boolean isOCDeployment, String changeModel, String assignmentGroup) {
    def initiatedBy = getContactNameByCiId(ciIdApplicationService)
    def assignedTo = getRacfNumberByEmail(si_git.emailOfLastCommit())
    def description = "COMMIT-ID: ${si_git.shortCommitId()}\nMessage: ${si_git.lastCommitMessage()}\n" +
        "Shared-Library: ${si_git.lastCommitIdOfSharedLibs()}\nChange-Bearbeiter: ${si_git.emailOfLastCommit()}"

    if (changeId == null) {
        def title = "SDA-Service ${serviceGroup}-${serviceName}"
        if (!isOCDeployment) {
            title = "SDA ${serviceGroup}-${serviceName}"
        }

        changeId = createStandardChange(ciIdApplicationService, initiatedBy, assignedTo, requestedEndDate, description,
            changeCoordinator, title, isOCDeployment, changeModel, assignmentGroup)

        changeToPlanAndSchedule(changeId)
        changeToExecution(changeId, plannedStart, plannedEnd)
    }

    changeToCmdbUpdated(changeId, implementationStart, implementationEnd, implementationComments)
    changeToPostImplementationReview(changeId)

  	def reviewResults = ""
    if (isOCDeployment) {
      reviewResults = getReviewResults(serviceGroup, serviceName)
    }

    changeToClosure(changeId, reviewResults)
    attachBuildLog(changeId)
}

/**
 * Creates a standard change for error path, deployment of service has failed.
 */
private createStandardChangeForFallBack(String serviceGroup, String serviceName, String ciIdApplicationService, String requestedEndDate, String implementationStart, String implementationEnd, String implementationComments,
    String plannedStart, String plannedEnd, String fallbackComment, String changeId, boolean isOCDeployment, String changeModel, String assignmentGroup) {
    def initiatedBy = getContactNameByCiId(ciIdApplicationService)
    def assignedTo = getRacfNumberByEmail(si_git.emailOfLastCommit())

    def description = "COMMIT-ID: ${si_git.shortCommitId()}\nMessage: ${si_git.lastCommitMessage()}\n" +
        "Shared-Library: ${si_git.lastCommitIdOfSharedLibs()}\nChange-Bearbeiter: ${si_git.emailOfLastCommit()}"

    if (changeId == null) {
        def title = "SDA-Service ${serviceGroup}-${serviceName}"
        if (!isOCDeployment) {
            title = "SDA ${serviceGroup}-${serviceName}"
        }

        changeId = createStandardChange(ciIdApplicationService, initiatedBy, assignedTo, requestedEndDate, description,
            changeCoordinator, title, isOCDeployment, changeModel, assignmentGroup)

        changeToPlanAndSchedule(changeId)
        changeToExecution(changeId, plannedStart, plannedEnd)
    }

    changeUpdate(changeId, implementationStart, implementationEnd, implementationComments)
    changeToFallback(changeId, fallbackComment)
    changeToPostImplementationReview(changeId)
    changeToClosureByError(changeId, isOCDeployment)
    attachBuildLog(changeId)
}

/**
 * Exception will be thrown if a time period conflict exists for given parameters.
 */
public boolean checkTimePeriodConflict(String ciIdApplicationService, String requestedEndDate, String changeCoordinator, 
    String plannedStart, String plannedEnd, String changeModel) throws Exception {
    echo "[its360] check time period conflict for application service ci-Id: '${ciIdApplicationService}' plannedStart: '${plannedStart}' and plannedEnd: '${plannedEnd}'"

    ciIdBusinessService = getParentCiId(ciIdApplicationService)

    def json = getJsonFromFile("timeperiod.json")
    json.Change.RequestedEndDate = requestedEndDate
    json.Change.header.ChangeCoordinator = changeCoordinator
    json.Change.middle.Assets = ciIdApplicationService
    json.Change.Service = ciIdBusinessService
    json.Change.header.PlannedStart = plannedStart
    json.Change.header.PlannedEnd = plannedEnd
    json.Change.header.ChangeModel = changeModel

    def object = createResource("changes", json.toString(), "/false/action/timeperiodConflictCalc")

    if(object.Change.TPConflict == 1) {
        throw new BusinessException("[its360] Time period conflict exists between ${plannedStart} and ${plannedEnd} for given ci-id ${ciIdApplicationService}. A deployment within this time is not possible because of maintanance activities, please contact your change-manager or create a normal change, for more details see: $DOCUMENTATION_URL")
    }

    return true
}

/**
 * Update the implementation comments of the change object
 * given comment will be added to existing comments
 */
public updateImplementationComments(String changeId, String implementationComment) {
    echo "[its360] implementationComment of change '$changeId' will be updated"

    obj = getChangeResource(changeId)

    def List<String> comments = []
    if(obj.Change.'description.structure'.ImplementationComments != null) {
        comments = obj.Change.'description.structure'.ImplementationComments
    }

    comments.add(implementationComment)
 
    def json = getJsonFromFile("changeupdate.json")
    json.Change.'description.structure'.ImplementationComments = comments

    updateResource("changes", changeId, json.toString())    
}

/**
 * Attachment will be added to given change object
 */
public boolean addAttachment(String changeId, File file) throws Exception {
    retry({
        echo "[its360] add attachment '${file.getAbsolutePath()}' to change '$changeId' "

        withCredentials([usernamePassword(credentialsId: 'its360', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {

            String authString = "${USERNAME}" + ":" + "${PASSWORD}"
            authString = authString.getBytes().encodeBase64().toString()

            def url = new URL("${ITSM_REST_API}/changes/$changeId/attachments")
            HttpURLConnection http = (HttpURLConnection)url.openConnection()
            
            http.setRequestProperty("Content-Type", "application/octet-stream")
            http.setRequestProperty("Content-Disposition", "attachment; filename=" + file.getName())
            http.setRequestProperty("Authorization", "Basic ${authString}")
            http.requestMethod = 'POST'
            http.setDoOutput(true)

            def os, fis
            
            try {
                os = http.getOutputStream()
                
                fis = new FilePath(Jenkins.getInstance().getComputer(env['NODE_NAME']).getChannel(), file.getAbsolutePath()).read()

                def StringBuffer sb = new StringBuffer();

                byte[] buf = new byte[1024];
                echo "DO NOT REMOVE THIS COMMENT !!"
                while (fis.available() > 0) {
                    os.write(buf, 0, fis.read(buf));
                }

                if (http.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new Exception(http.getErrorStream().toString())
                }
            } catch (Exception e) {
                throw e;
            } finally {
                fis?.close()
                os?.close()
                http.disconnect()
            }
        }

        return true
    })
}

/**
 * Change will be updated to the phase 'Fall-Back'
 */
public void changeToFallback(String changeId, String fallbackComment) {
    echo "[its360] change '$changeId' will be updated to the phase 'Fall-Back'"
 
    def json = getJsonFromFile("fallback.json")
    json.Change.middle.BackOutComments = "Das Deployment ist fehlgeschlagen. Grund siehe Fehlermeldung: " + fallbackComment

    updateResource("changes", changeId, json.toString())
}

/**
 * Change will be updated to the phase 'Execution'
 */
public void changeToExecution(String changeId, String plannedStart, String plannedEnd) {
    echo "[its360] change '$changeId' will be updated to the phase 'Execution'"
  

    def json = getJsonFromFile("execution.json")
    json.Change.header.PlannedStart = plannedStart
    json.Change.header.PlannedEnd = plannedEnd

    updateResource("changes", changeId, json.toString())
}

/**
 * Change will be updated to the phase 'Plan and Schedule'
 */
public void changeToPlanAndSchedule(String changeId) {
    echo "[its360] change '$changeId' will be updated to the phase 'Plan and Schedule'"
    
    def json = getJsonFromFile("planschedule.json")
    updateResource("changes", changeId, json.toString())
}

/**
 * Creates a standard change for a given configuration item.
 * Returns a changeid
 */
private createStandardChange(String ciIdApplicationService, String initiatedBy, String assignedTo, String requestedEndDate, String description,
    String changeCoordinator, String title, boolean isOCDeployment, String changeModel, String assignmentGroup) throws Exception {
    echo "[its360] create a standard-change for application service with ci-Id ${ciIdApplicationService}"

    ciIdBusinessService = getParentCiId(ciIdApplicationService)

    def scope = []
    if (isOCDeployment) {
        scope.add("Zu aktualisierende: Micro-Service auf der SDA-Plattform")
    } else {
        scope.add("Zu aktualisierende: " + changeModel)
    }
    scope.add("Benötigte Funktionsstellen: keine")
    scope.add("Auswirkung während der Umsetzung: Keine Beeinträchtigung während der Aktualisierung.")

    def json = getJsonFromFile("standardchange.json")
    json.Change.RequestedEndDate = requestedEndDate
    json.Change.Scope = scope
    json.Change.'description.structure'.Description = description
    json.Change.header.ChangeCoordinator = changeCoordinator
    json.Change.header.Title = title
    json.Change.header.InitiatedBy = initiatedBy
    json.Change.header.AssignedTo = assignedTo
    json.Change.header.AssignmentGroup = assignmentGroup
    json.Change.header.ChangeModel = changeModel
    json.Change.middle.Assets = ciIdApplicationService
    json.Change.Service = ciIdBusinessService

    def object = createResource("changes", json.toString())
    return object.Change.header.ChangeID
}

/**
 * Returns TRUE if normal change can be handled
 * Returns FALSE if standard change can be handled
 * otherwise exception will be thrown
 */
public boolean preCheckChanges(String ciIdApplicationService) throws Exception {
    echo "[its360] precheck changes for application ci-id $ciIdApplicationService"

    json = getNormalChangesByCiId(ciIdApplicationService)

    def totalSize = json.'@count'
    println "[its360] $totalSize normal changes found"

    if (totalSize == 0) {
     throw new BusinessException ("[its360] No normal change exists, please create one, for more details see: $DOCUMENTATION_URL")
    }

    // check if 1 normal change in phase 'deployment exists'
    def phaseDeployment  = json.content.Change.findAll { it.header.Phase == 'Deployment' }
    if (phaseDeployment.size() == 1) {
      println "[its360] ${phaseDeployment.size()} normal change exists in phase 'Deployment', NORMAL-CHANGE can be handled !"
      return true
    } else if (phaseDeployment.size() > 1) {
        throw new BusinessException ("[its360] ${phaseDeployment.size()} normal changes exist, expected was 1, for more details see: $DOCUMENTATION_URL")
    }

    // check if all changes beeing in phase closure, PIR or CMDB Update all phases have to be handeled same
    def phaseClosurePir  = json.content.Change.findAll { it.header.Phase == 'Closure' || it.header.Phase == 'Post Implementation Review' || it.header.Phase == 'CMDB Update' }
    if (phaseClosurePir.size() > 0) {
        if (phaseClosurePir.size() == totalSize) {
          println "[its360] All ${phaseClosurePir.size()} existing changes are in phase 'Closure' or 'Post Implementation Review' or 'CMDB Update', STANDARD-CHANGE can be handled !"
          return false
        } else {
            throw new BusinessException ("[its360] ${phaseClosurePir.size()} of $totalSize normal changes found are not in phase 'Closure' or 'Post Implementation Review' or 'CMDB Update'. All normal changes have to be in phase 'Closure' or 'Post Implementation Review' or 'CMDB Update' to continue the deployment, for more details see: $DOCUMENTATION_URL")
        }
    } else {
        throw new BusinessException ("[its360] normal changes in phase 'Deployment' or 'Closure' or 'Post Implementation Review' or 'CMDB Update' do not exist, for more details see: $DOCUMENTATION_URL")
    }
}

/**
 * Returns Normal-Change-ID in phase "Deployment" for given application service ci-id, if there is exactly one.
 *
 * If there are more than 1 or there are none, an exception is thrown.
 */
 public String getNormalChangeIdByCiId(String ciId) throws Exception {
    changeId = getChangeBy(ciId, "Normal Change", "Deployment")

    if (changeId == null) {
        throw new BusinessException("[its360] Normal-Change for Configurationitem '$ciIdApplicationService' in phase '$phase' not found, for more details see: $DOCUMENTATION_URL")
    }

    return changeId
}

/**
 * Returns Standard-Change-ID in phase "Execution" for given application service ci-id, if there is exactly one.
 *
 * If there are more than 1, an exception is thrown
 *
 * If there are none, null is returned.
 */
 public String getStandardChangeIdByCiId(String ciId) throws Exception {
    return getChangeBy(ciId, "Standard Change", "Execution")
}

/**
 * Returns a Change-ID for given application service ci-id and a given category ("Normal Change" or "Standard Change").
 *
 * Returns null, when NO changes in phase "Deployment" were found.
 *
 * Throws BusinessException, when more than one change in phase "Deployment" was found.
 */
private String getChangeBy(String ciId, String category, String phase) throws Exception {
    echo "[its360] search '${category}' by ci-id  '$ciId' of application service"

    resource = "changes"
    criteria = "assets=${quote(ciId)} and category=${quote(category)} and current.phase=${quote(phase)}"

    jsonResult = getResources(resource, criteria)

    changeCount = jsonResult.'@count'
    changeId = null

    if(changeCount == 1) {
        changeId = jsonResult.content.Change.header.ChangeID[0]
    } else if (changeCount > 1 ) {
        throw new BusinessException("[its360] Expected 1 '${category}' for Configurationitem '$ciId' in phase '$phase', but found $changeCount, for more details see: $DOCUMENTATION_URL")
    }

    return changeId
}

/**
 * Returns true if the change is in phase "Deployment"
 * and the deployment time is inside the planned time,
 * otherwise exception will be thrown
 */
private boolean checkPreConditionsOfNormalChange(String changeId) {
    return checkPreConditionsForChange(changeId, "Deployment")
}

/**
 * Returns true if the change is in phase "Execution"
 * and the deployment time is inside the planned time,
 * otherwise exception will be thrown
 */
private boolean checkPreConditionsOfStandardChange(String changeId) {
    return checkPreConditionsForChange(changeId, "Execution")
}

/**
 * Returns true if the change is in the expected phase
 * and the deployment time is inside the planned time,
 * otherwise exception will be thrown
 */
private boolean checkPreConditionsForChange(String changeId, String expectedPhase) {
    echo "[its360] check pre-conditions of change '$changeId'"

    def dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
    def sdf = new SimpleDateFormat(dateFormat)
    sdf.setTimeZone(TimeZone.getTimeZone("UTC"));

    change  = getChangeResource(changeId)

    phase = change.Change.header.Phase
    plannedStart = change.Change.header.PlannedStart
    plannedEnd = change.Change.header.PlannedEnd

    // change must be in expected phase
    if (phase != expectedPhase) {
        throw new BusinessException("[its360] Expected phase of Change '$changeId' to be '${expectedPhase}' but was '$phase', for more details see: $DOCUMENTATION_URL")
    }

    // current deployment datetime must be inside planned datetime
    def currentDate = new Date()
    def start = sdf.parse(plannedStart)
    def end = sdf.parse(plannedEnd)

    if (!((currentDate.after(start) || currentDate.compareTo(start) == 0) &&
          (currentDate.before(end)  || currentDate.compareTo(end) == 0))) {
            throw new BusinessException("[its360] current deloyment time ${currentDate} not between ${start} - ${end}, for more details see: $DOCUMENTATION_URL")
    }

    return true
}

/**
 * Change will be updated to the phase 'CMDB Update'
 */
public void changeToCmdbUpdated(String changeId, String implementationStart, String implementationEnd, String implementationComments) {
    echo "[[its360]] change '$changeId' will be updated to the phase 'CMDB Updated'"
  

    def json = getJsonFromFile("cmdbupdate.json")
    json.Change.ImplementationStart = implementationStart
    json.Change.ImplementationEnd = implementationEnd
    json.Change.'description.structure'.ImplementationComments = implementationComments

    updateResource("changes", changeId, json.toString())
}

/**
 * Change will updated
 */
public void changeUpdate(String changeId, String implementationStart, String implementationEnd, String implementationComments) {
    echo "[its360] change '$changeId' will be updated."
  

    def json = getJsonFromFile("changeupdatefull.json")
    json.Change.ImplementationStart = implementationStart
    json.Change.ImplementationEnd = implementationEnd
    json.Change.'description.structure'.ImplementationComments = implementationComments

    updateResource("changes", changeId, json.toString())
}

/**
 * Change will be updated to the phase 'Post Implementation Review'
 */
public void changeToPostImplementationReview(String changeId) {
    echo "[its360] change '$changeId' will be updated to the phase 'Post Implementation Review'"
    
    def json = getJsonFromFile("pir.json")
    updateResource("changes", changeId, json.toString())
}

/**
 * Change will be updated to the phase 'Closure'
 */
public void changeToClosure(String changeId, String reviewResults) {
    echo "[its360] change '$changeId' will be updated to the phase 'Closure'"

    def json = getJsonFromFile("closure.json")
    json.Change.ReviewResults = reviewResults
    updateResource("changes", changeId, json.toString())
}

/**
 * Change will be updated to the phase 'Closure' when error occurs
 */
public void changeToClosureByError(String changeId, boolean isOCDeployment) {
    echo "[its360] change '$changeId' will be updated to the phase 'Closure'"

    def reviewResults = []
    if (isOCDeployment) {
        reviewResults.add("Deployment ist fehlgeschlagen. Alte Version des Services steht weiterhin zur Verfügung.")
    } else {
        reviewResults.add("Änderung ist fehlgeschlagen - die bisherige Version steht weiterhin zur Verfügung.")
    }

    def json = getJsonFromFile("closurewitherror.json")
    json.Change.ReviewResults = reviewResults

    updateResource("changes", changeId, json.toString())
}

/**
 * Checks if Change is locked by user. 
 * A test update will be execute on the change
 * if returncode 3 will be occured a businessexception will be thrown
 */
private checkIfChangeLockedByUser(String changeId) {
    echo "[its360] check if change '$changeId' locked by user"
 
    def json = getJsonFromFile("changeempty.json")
    try {
        updateResource("changes", changeId, json.toString())
    }
    catch (Exception e) {
        if (e.getMessage() =~ /.*\"ReturnCode\": 3/) {
            throw new BusinessException("[its360] The operation failed because the change '$changeId' is locked, for more info: http://wiki.system.local/x/3vUdCw  \n ${e.getMessage()}")
        }
    }
}

/**
 * Return parent ci-id for given child ci-Id.
 */
 private String getParentCiId(String childCiId) throws Exception {
    echo "[its360] search parent ci-id for given child ci-id '$childCiId'"

    resource = "Relationships"
    criteria = "ChildCIs=${quote(childCiId)}"
    
    jsonResult = getResources(resource, criteria, "?", true)

    // TODO: A CI may have more than one parent! At least one of these parents should have "SDA Service" as parent itself!
    if(jsonResult.'@count' > 0) {
        ciId = jsonResult.content.Relationship.ParentCI.get(0)
    } else {
        throw new BusinessException("[its360] The configured CI-ID '$childCiId' has not the expected parent Item. Please check your configured CI-Id, for more details see: $DOCUMENTATION_URL")
    } 
    return ciId
 }

/**
 * Returns the change object
 */
private getChangeResource(String changeId) {
    echo "[its360] return change by id '$changeId'"

    resource = "changes"
    criteria = changeId

    return getResources(resource, criteria, "/")
} 

/**
 * Executes an GET REST-Call and retern the itsm resource.
 */
private String getResources(String resource, String criteria, String queryOperator="?", boolean withExpandView=false) {
    retry({
        echo "[its360] get  Resource for query ' $resource$queryOperator$criteria'"

        RESULT_FILE="result.json"

        withCredentials([usernamePassword(credentialsId: 'its360', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {


            def conn = null
            try {
                String authString = "${USERNAME}" + ":" + "${PASSWORD}"
                authString = authString.getBytes().encodeBase64().toString()

                def url = new URL("${ITSM_REST_API}/${resource}" + queryOperator + URLEncoder.encode(criteria).replace("+", "%20") + (withExpandView ? "&view=expand" : ""))
                conn = url.openConnection()
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("ACCEPT", "application/json");
                conn.setRequestProperty("Authorization", "Basic ${authString}")
                conn.requestMethod = 'GET'
                jsonString = conn.content.text
            } catch (Exception e) {
                throwMappedException(e, conn)
            } finally {
                conn?.disconnect()
            }
        }
        writeFile file: "${RESULT_FILE}", text: "${jsonString}"
        jsonResult = readJSON file: "${RESULT_FILE}"

        return jsonResult
    })
}

/**
 * Update resource.
 * 
 * Example resource for a change: 
 * http://its360.system-a.local:31190/SM/9/rest/changes/C02014646
 */
private boolean updateResource(String resource, String criteria, String jsonPayload) throws Exception {
    retry({
        echo "[its360] update resource '$resource' with criteria '$criteria' and payload '$jsonPayload'"

        withCredentials([usernamePassword(credentialsId: 'its360', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
            
            String authString = "${USERNAME}" + ":" + "${PASSWORD}"
            authString = authString.getBytes().encodeBase64().toString()

            def url = new URL("${ITSM_REST_API}/${resource}/" + URLEncoder.encode(criteria).replace("+", "%20"))
            HttpURLConnection http = (HttpURLConnection)url.openConnection()
            http.setRequestProperty("Content-Type", "application/json");
            http.setRequestProperty("ACCEPT", "application/json");
            http.setRequestProperty("Authorization", "Basic ${authString}")
            http.setDoOutput(true)
            http.requestMethod = 'POST'

            OutputStream os = http.getOutputStream()
            try {
                byte[] input = jsonPayload.getBytes("utf-8");
                os.write(input, 0, input.length);  
                http.getInputStream();
            } catch (Exception e) {
                throwMappedException(e, http)
            } finally {
                os.close()
                http.disconnect()
            }
        }

        return true
    })
}

/**
 * Creates a resource e.g a change-object
 */
private createResource(String resource, String jsonPayload, String uriPath="") throws Exception {
    retry({
        echo "[its360] create resource '$resource' payload '$jsonPayload'"

        withCredentials([usernamePassword(credentialsId: 'its360', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {

            String authString = "${USERNAME}" + ":" + "${PASSWORD}"
            authString = authString.getBytes().encodeBase64().toString()

            def url = new URL("${ITSM_REST_API}/${resource}${uriPath}")
            HttpURLConnection http = (HttpURLConnection)url.openConnection()
            http.setRequestProperty("Content-Type", "application/json")
            http.setRequestProperty("ACCEPT", "application/json");
            http.setRequestProperty("Authorization", "Basic ${authString}")
            http.setDoOutput(true)
            http.requestMethod = 'POST'
            
            OutputStream os = http.getOutputStream()
            try {
                byte[] input = jsonPayload.getBytes("utf-8")
                os.write(input, 0, input.length);
                
                //read output
                BufferedReader br = new BufferedReader(new InputStreamReader(http.getInputStream(), "utf-8"))
                StringBuilder response = new StringBuilder()
                String responseLine = null
                
                while ((responseLine =  br.readLine()) != "" ) {
                    if(responseLine == null) {
                        break
                    }
                    response.append(responseLine.trim())
                }
                
                return new JsonSlurper().parseText(response.toString())
            } catch (Exception e) {
                throwMappedException(e, http)
            } finally {
                os.close()
                http.disconnect()
            }
        }
    })
}

/**
 * Depends on some predefined error returncodes, 
 * an occured exception will be mapped to a business exception before throwing
 */
private throwMappedException(exception, connection) {
    def NOT_AUTHORIZED = "-4"
    def RESOURCE_LOCKED = "3"

    TECHNICAL_EXCEPTION_MESSAGE = getErrorStreamAsText(connection)

    // weird bug, missing details, see failed job:
    // https://sda-jenkins.system.local/job/BiPRO/job/shipment-import-jobs/job/master/33/consoleFull
    println "******** DEBUG INFO ************" // TODO: remove me
    println exception.toString()
    println "******** ********** ************"
  	println TECHNICAL_EXCEPTION_MESSAGE
    println "******** DEBUG INFO ************"
  
    returnCodesToBeMapped = [NOT_AUTHORIZED, RESOURCE_LOCKED]

    def match = (TECHNICAL_EXCEPTION_MESSAGE =~ /.*\"ReturnCode\":.([-]?\d+).*/)

    if (match) {
        returnCode = match.group(1)
    }

    // TODO: var returnCode is potentially undefined! (see if-construct before)
    if (returnCodesToBeMapped.contains(returnCode)) {
        throw new BusinessException("[its360] $TECHNICAL_EXCEPTION_MESSAGE , for more details see: $DOCUMENTATION_URL")
    }

    throw new Exception(exception.getMessage() + "\n" + TECHNICAL_EXCEPTION_MESSAGE)
}

/**
 * Return text with double quotes
 */
private String quote(String text) {
    return "\"$text\""
}


private def getJsonFromFile(String resourceFile) {
    readJSON text: libraryResource("de/signaliduna/its360/$resourceFile")
}

/**
 * Returns list of Normal-Changes as json object for given application service ci-id.
 * Abandonded Changes will be excluded from list.
 */
 private getNormalChangesByCiId(String ciIdApplicationService) throws Exception {
    echo "[its360] search normal change by ciId-ApplicationService '$ciIdApplicationService'"

    category = "Normal Change"
    resource = "changes"
    phase = "Abandoned"

    criteria = "assets=${quote(ciIdApplicationService)} and category=${quote(category)} and current.phase<>${quote(phase)}"

    return getResources(resource, criteria, "?", true)
}

/**
 * Attach list of Files to change.
 */
private boolean addAttachments(def changeId, def files) throws Exception {
    files?.each {
        addAttachment(changeId, new File("${env.WORKSPACE}/$it"))
    }
}

/**
 * Get ConfigurationItem for service to be deployed
 */
private getConfigurationItem(String serviceGroup, String serviceName) throws Exception {
    echo "[its360] read from secret-text in jenkins credentials store with named '${serviceGroup}-${serviceName}-ciid' the ciid"

    def ciId
    try {
        withCredentials([string(credentialsId: "${serviceGroup}-${serviceName}-ciid", variable: 'TOKEN')]) {
            ciId = "${TOKEN}"

            if (!(ciId =~ /(CI|AM|KM)\d{8}/)) {
                throw new BusinessException("[its360] The ID must start with either CI, AM or KM followed by 8 numbers like CI17835289, actual value was '$ciId', for more details see: $DOCUMENTATION_URL")
            }
        } 
    } catch (BusinessException e) {
        throw e
    } catch (Exception e) {
        throw new BusinessException("[its360] ${e.getMessage()}, please create a secret-text named '${serviceGroup}-${serviceName}-ciid' in jenkins credentials-store for configuration item, for more details see: $DOCUMENTATION_URL")
    }

    echo "[its360] read ciid from secret '${serviceGroup}-${serviceName}-ciid' is '$ciId'"

    return ciId.trim()
}

/**
 * Get ConfigurationItem for service to be deployed
 */
private getConfigurationItem(String alternativeSecretId) throws Exception {
    echo "[its360] read from secret-text in jenkins credentials store with named '${alternativeSecretId}-ciid' the ciid"

    def ciId
    try {
        withCredentials([string(credentialsId: "${alternativeSecretId}-ciid", variable: 'TOKEN')]) {
            ciId = "${TOKEN}"

            if (!(ciId =~ /(CI|AM|KM)\d{8}/)) {
                throw new BusinessException("[its360] The ID must start with either CI, AM or KM followed by 8 numbers like CI17835289, actual value was '$ciId', for more details see: $DOCUMENTATION_URL")
            }
        }
    } catch (BusinessException e) {
        throw e
    } catch (Exception e) {
        throw new BusinessException("[its360] ${e.getMessage()}, please create a secret-text named '${alternativeSecretId}-ciid' in jenkins credentials-store for configuration item, for more details see: $DOCUMENTATION_URL")
    }

    echo "[its360] read ciid from secret '${alternativeSecretId}-ciid' is '$ciId'"

    return ciId.trim()
}

/**
 * Returns a contact name for a given application ci-id
 * Example: "000000000264342-CONT"
 */
private getContactNameByCiId(String ciIdApplicationService) {
    echo "[its360] search contact name of ci-id '$ciIdApplicationService'"

    resource = "devices"
    criteria = "$ciIdApplicationService"
    jsonResult = getResources(resource, criteria, "/")

    if(jsonResult.Device.ContactName != null) {
        return jsonResult.Device.ContactName
     } else {
        throw new BusinessException("ContactName for ci-id '$ciIdApplicationService' not exists, for more details see: $DOCUMENTATION_URL")
     }
}

 /**
 * Returns the Racf U-Nummer for a given email adress.
 * If no Racf number is available only info message will be logged.
 */
 private String getRacfNumberByEmail(String email) {
    echo "[its360] search u-nummer for given email $email'"

    resource = "sdaoperators"
    criteria = "email=${quote(email)}"

    jsonResult = getResources(resource, criteria, "?", true)

    for(sdaoperator in jsonResult?.content?.sdaoperator){
        if (sdaoperator.Name ==~ /[U|u]\d{6}/) {
            return sdaoperator.Name
        }
    }

    echo "[its360] U-Number for given email '$email' not found !"
 }

/**
 * Returns the review results of a deployed service.
 * Including openshift url of prod openshift and status of all pods for given namespace.
 */
private getReviewResults(String serviceGroup, String serviceName) {
    si_openshift.login(serviceGroup, serviceName, TargetSegment.prd)
    return "Openshift-Console: ${si_openshift.getOpenshiftUrl(TargetSegment.prd)}/console/project/$serviceGroup-$serviceName-prd/overview\n${si_openshift.getAllPods()}"
}

/**
 * Current build log will be attached to the change
 */
private attachBuildLog(String changeId) {
    echo "[its360] attach build log to change '$changeId'"

    writeFile file: "build_${env.BUILD_ID}.log", text: "${getJenkinsBuildLog()}" 
    addAttachment(changeId, new File("${WORKSPACE}/build_${env.BUILD_ID}.log"))
}

/**
 * Returns the current build log from jenkins.
 */
private String getJenkinsBuildLog() {
    withCredentials([usernamePassword(credentialsId: 'its360', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
        String authString = "${USERNAME}" + ":" + "${PASSWORD}"
        authString = authString.getBytes().encodeBase64().toString()

        def url = new URL("${env.BUILD_URL}/consoleText")
        def conn = url.openConnection()

        conn.setRequestProperty("Content-Type", "application/text");
        conn.setRequestProperty("ACCEPT", "application/text");
        conn.setRequestProperty("Authorization", "Basic ${authString}")
        conn.requestMethod = 'GET'
        return conn.content.text
    }
}

/**
 * Notify the change-manager by sending an email if a technical error occured.
 */
private notifyChangeManager(String changeId, String serviceGroup, String serviceName) {
    if (TECHNICAL_EXCEPTION_OCCURED) {
        subject  = "[SDA-Service: $serviceGroup-$serviceName] technical error ocured for Change: $changeId"
        body = """
            technical error ocured for Change: $changeId\nSDA-Service: $serviceGroup-$serviceName
            Build: ${env.BUILD_URL}
            Message: ${TECHNICAL_EXCEPTION_MESSAGE}
        """
        si_jenkins.sendMail(NOTIFY_CHANGE_MANAGER_FROM, NOTIFY_CHANGE_MANAGER_TO, subject, body)
    }
}

/**
 * All thrown exception will be catched and handled here.
 * Exceptions from type business will stop the current build.
 */
private handleException (Exception e) throws Exception {
    if (IGNORE_ALL_ITS360_EXCEPTIONS) {
        echo "┳┻|\n┻┳|\n┳┻| _       [its360] Exception ${e.getClass()} occured. Currently all its360 errors will be skipped for a transition period.\n┻┳| •.•)\n┳┻|⊂ﾉ       ${e.getMessage()}.\n┻┳|\n┳┻|"
    } else if (e instanceof BusinessException) {
		throw e
	} else {
        println "TECHNICAL_EXCEPTION_OCCURED: ${e.getMessage()}"
		TECHNICAL_EXCEPTION_OCCURED = true
    } 
}

/**
 * This Method checks if a ciid exists in the Jenkins credentials store.
 * Throws an exception prints only a log if no one exists.
 */
public errorForDeploymentWithoutConfiguredCiId(String serviceGroup, String serviceName) {
    try {
        getConfigurationItem(serviceGroup, serviceName)
    }
    catch (Exception e) {
        String errorMessage= """"
        ****************************************************  !!! ACHTUNG kein Konfigurationselement gefunden !!!!  ******************************************
        *************                                                                                                                             ************
        *************       Für das deployen nach Produktion wird unbedingt ein CI-Element benötigt, um automatisiert ein                         ************
        *************       Standard-Change anzulegen. Für weitere Info siehe http://wiki.system.local/x/3vUdCw                                   ************
        *************                                                                                                                             ************
        ******************************************************************************************************************************************************
        """

        throw new Exception(errorMessage)
    }
}

/**
 * Text in Error-Stream of http-response will be returned as text.
 */
private getErrorStreamAsText(HttpURLConnection http) {

    InputStream is = http?.getErrorStream();

    if (is != null) {
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(is));
        StringBuilder response = new StringBuilder()
        String responseLine = null
        while ((responseLine = bufferedReader.readLine()) != "" ) {
            if(responseLine == null) {
                break
            }
            response.append(responseLine.trim() + "\n")
        }

        bufferedReader.close()
        return response.toString()
    }
}

/**
 * Text in Error-Stacktrace will be returned as text.
 */
private String getErrorStacktraceAsText(Exception e) {

	StringWriter sw = new StringWriter()
	PrintWriter pw = new PrintWriter(sw)
	e.printStackTrace(pw)
	return sw.toString()
}

/**
 * Retry method for API-Calls against its360 API-Server.
 */
private retry(Closure closure) {
    int retry = 0
    while (retry < maxRetry) {
        try {
            return closure.call()
        }
        catch(Exception exp) {
            retry++
            print("Retry ${retry}/${maxRetry}\n${getErrorStacktraceAsText(exp)}")
            if (retry == maxRetry) {
                throw new Exception("API call fails after ${maxRetry} retries.")
            } else {
                print("Retry after ${waitBetweenRetryInSeconds} seconds")
                sleep(waitBetweenRetryInSeconds)
            }
        }
    }
}
