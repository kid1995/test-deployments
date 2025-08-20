import groovy.io.FileType
import hudson.FilePath
import de.signaliduna.TargetSegment
import com.cloudbees.groovy.cps.NonCPS

@groovy.transform.Field
final artilleryFolder = "${env.WORKSPACE}/.artillery"

@groovy.transform.Field
final _jenkinsWorkspace = env.WORKSPACE

@groovy.transform.Field
final _testFolder = "loadtest"

@groovy.transform.Field
final artilleryCall = "node_modules/artillery/bin/artillery"

/**
 * Run a specified artillery command
 * 
 * @param command The artillery command to execute.
 * @param args Optional arguments passed to the command.
 */
private void _execCmd(String command, String args = '') {
   si_npm.nodeExecCmd(artilleryFolder, "${artilleryCall} ${command}", args)
}

/**
 * Convenience for printing some info into the Jenkins output
 *
 * @param message The text to be printed out
 */
private void _printLog(String message) {
   println "######## ${message} at ${new Date().toString()} ########"   
}

/**
 * Prepare a local artillery installation into the default artillery folder 
 * To solve the node-version dependency this call gets its own version value
 * It is followed by a dino-call to prove it works
 *
 * @param nodeVersion  Optional node-version that has to be used for artillery
 */
private void _prepareArtillery(String nodeVersion =  "12.13.1") {

    sh "mkdir -p ${artilleryFolder}"
    si_npm.node_version(nodeVersion)
    si_npm.npmExecCmd(artilleryFolder, "install --save", "artillery")
    si_npm.npmExecCmd(artilleryFolder, "install --save", "artillery-plugin-expect")
    si_npm.npmExecCmd(artilleryFolder, "install --save", "artillery-plugin-metrics-by-endpoint")
    _execCmd("dino")
}

/**
 * Execute test defined in a service folder, automatically generate report
 *
 * @param testFolder The name of the folder where the test resides.
 * @param fileName The name of the file with the testdefinition.
 * @param args Optional arguments passed to the command.
 */
private void _executeTest(String testFolder, String fileName, String args = '') {
    def reportFile = "${fileName.take(fileName.lastIndexOf('.'))}.json"
    def htmlFile = "${fileName.take(fileName.lastIndexOf('.'))}.html"
    try {
        _execCmd("run", "${testFolder}/${fileName} -o ${testFolder}/${reportFile} " + args)
    } finally { 
        _execCmd("report", "${testFolder}/${reportFile} -o ${testFolder}/${htmlFile}")
        _printLog("HTML report generated - you find it in ${testFolder}/${htmlFile}")
    }
}

/**
 * Collect all Yaml files in targetfolder
 *
 * We need this because of some restrictions in Jenkins' Groovy methodcall model:
 * https://jenkins.io/redirect/pipeline-cps-method-mismatches/
 *
 * @param testFolder The folder where to search for yaml files.
 */
@NonCPS
private String [] _collectYamlFiles(String testFolder)
{
    def files = []
    def rootPath = new FilePath(Jenkins.getInstance().getComputer(env['NODE_NAME']).getChannel(), testFolder)

    for (FilePath fp : rootPath.list()) {
        if(fp.toString().endsWith("yaml")) {
            files.add(fp.toString().tokenize('/')[-1])
        }
    }
    return files
}


/**
 * Run all tests, basing on extension .yaml in specified service-folder. 
 * If necessary artillery will be installed, else an existing local artillery is used
 *
 * @param serviceGroup Serviceinformation for openshift-query.
 * @param serviceName Serviceinformation for openshift-query.
 * @param appBackendName Name of the app
 * @param appBackendFolder Where the loadtest-folder resides
 * @param targetSegment Environment to run test. 
 * @param additionalParams Option, map support future enhancements
 *        - TrailingUrlParts - path elements to be appended to standard backend url
 *        - ArtilleryArgs - arguments for the artillery.io commandline
 * @param trailingUrlPart Optional part that will be appended at generated url
 * @param artilleryArgs Optional arguments passed to the command.
 */
public void runTests(String appBackendFolder, String serviceGroup, String serviceName, String appBackendName, TargetSegment targetSegment, Map additionalParams = [:]) {
    def artilleryArgs = additionalParams.containsKey("ARTILLERY_ARGS") ? additionalParams["ARTILLERY_ARGS"] : ""
    def urlScheme = additionalParams.containsKey("URL_SCHEME") ? additionalParams["URL_SCHEME"] : "https"
    def backendUrl = additionalParams.containsKey("BACKEND_URL") ? additionalParams["BACKEND_URL"] : ""

    // if no userdefined url given => automagic!
    if(!backendUrl) {
        def backendResourceName = si_openshift.getApplicationResourceName(appBackendName, targetSegment)
        def projectUrl = si_openshift.getProjectUrl(serviceGroup, serviceName, targetSegment)
        backendUrl = "${backendResourceName}.${projectUrl}"
    }
        
    backendUrl = "${urlScheme}://${backendUrl}"

    // if url ends with / test fails - assumes that backendUrl not empty
    if(backendUrl[-1] == "/") {
        backendUrl = backendUrl[0..-2]
    }

    def testFolder = "${_jenkinsWorkspace}/${appBackendFolder}/${_testFolder}"
    def allArgs = "--environment ${targetSegment.toString()} --target ${backendUrl} " + artilleryArgs

    _prepareArtillery()

    for(fName in _collectYamlFiles(testFolder)) {
        _printLog("Starting Loadtests ${fName} with Arguments: ${allArgs}")
        _executeTest(testFolder, fName, allArgs)
        _printLog("Finished Loadtests ${fName}")
    } 

    _publishReportsAsHTML(appBackendFolder)
}

/**
 * Adds HTML Report of Performancetests to Jenkins
 */
private void _publishReportsAsHTML(String appBackendFolder) {
    publishHTML(
              [
                allowMissing: true, 
                alwaysLinkToLastBuild: true, 
                includes: '**/*.html', 
                keepAll: true, 
                reportDir: "${appBackendFolder}/${_testFolder}", 
                reportFiles: '*.html', 
                reportName: 'Artillery Report', 
                reportTitles: ''
              ]
            )
}

