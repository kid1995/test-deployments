/**
 * Adds a link to the jenkins build description by the given name and url.
 * @param name - The name of the link.
 * @param url - The link itself.
 */
public void addLinkToDescription(String name, String url) {
    String newLine = "<a href=" + url + ">" + name + "</a>"
    if (currentBuild.description == null) {
        currentBuild.description = newLine
    } else {
        currentBuild.description += "<br />" + newLine
    }
}

/**
 * Notify Bitbucket with jenkins build result.
 * @param buildProcess - The closure
 */
public void notifyBitbucket(Closure buildProcess) {
    printDeprecationWarning('notifyBitbucket', 'notify')
    try {
        // Notify bitbucket about build start
        doNotifyBitbucket()
        buildProcess()
        currentBuild.result = 'SUCCESS'
    } catch (Throwable exception) {
        currentBuild.result = 'FAILED'
        throw exception
    } finally {
        // Notify bitbucket about successful/failed build
        doNotifyBitbucket()
    }
}

public void notify(Map config, Closure buildProcess) {
    try {
        buildProcess()
        currentBuild.result = 'SUCCESS'
    } catch (Throwable exception) {
        currentBuild.result = 'FAILURE'
        throw exception
    } finally {
        // Notify backends about successful/failed build
        doNotify(config)
    }
}

/**
 Inform Bitbucket that the build job has started if Bitbucket notifications are enabled.
 Must be called after successful git checkout otherwise the notification won't be propagated to Bitbucket.
 **/
public void notifyInProgress(Map config) {
    if (config.get('Bitbucket', false)) {
        doNotifyBitbucket('INPROGRESS');
    }
}

public void doNotifyBitbucket(String buildStatus) {
    step([$class                       : 'StashNotifier',
          credentialsId                : 'jenkins_git_http',
          disableInprogressNotification: false,
          ignoreUnverifiedSSLPeer      : true,
          includeBuildNumberInKey      : false,
          prependParentProjectKey      : true,
          buildStatus                  : buildStatus,
          stashServerBaseUrl           : 'https://git.system.local'])
}

public void doNotifyBitbucket() {
    allocateNodeIfNecessary {
        doNotifyBitbucket(currentBuild.result)
    }
}


/**
 * Sends a notification email about the build result.
 *
 * This method sends an email notification with information about the current build status.
 * The recipients of the email are determined by the provided configuration.
 *
 * @param conf The configuration map containing the following keys:
 *             <ul>
 *             <li><code>from</code>: The sender's email address.</li>
 *             <li><code>to</code>: A list of default recipient email addresses.</li>
 *             <li><code>failureRecipients</code>: A list of additional recipient email addresses for failed builds (optional).</li>
 *             <li><code>successRecipients</code>: A list of additional recipient email addresses for successful builds (optional).</li>
 *             <li><code>informAboutSuccess</code>: A boolean indicating whether to send notifications for successful builds.</li>
 *             <li><code>serviceName</code>: The name of the service being built.</li>
 *             </ul>
 */
public void doNotifyPerMail(Map conf) {
    String subject = "${currentBuild.result == 'SUCCESS' ? '🟢' : '🔴'} ${currentBuild.result} – ${conf.serviceName}"
    String body = """Status: ${currentBuild.result}
Project: ${conf.serviceName}
Branch: ${env.BRANCH_NAME}
Build Number: ${env.BUILD_NUMBER}
Build URL: ${env.BUILD_URL}
"""

    if (currentBuild.result == 'FAILURE') {
        List<String> recipients = []
        recipients.addAll(conf.to ?: [])
        recipients.addAll(conf.failureRecipients ?: [])

        if (!recipients.isEmpty()) {
            sendMail(conf['from'], recipients.join(","), subject, body)
        }

    } else if (currentBuild.result == 'SUCCESS' && conf.get('informAboutSuccess', false)) {
        List<String> recipients = []
        recipients.addAll(conf.to ?: [])
        recipients.addAll(conf.successRecipients ?: [])

        if (!recipients.isEmpty()) {
            sendMail(conf['from'], recipients.join(","), subject, body)
        }
    }
}

/**
 * In some cases projects manage their node allocation inside the notify block.
 * If this is the case, we must allocate a node before calling the notify logic.
 */
private allocateNodeIfNecessary(Closure notifyLogic) {
    if (env.NODE_NAME == null) {
        node {
            notifyLogic()
        }
    } else {
        notifyLogic()
    }
}

private void doNotify(Map config) {
    allocateNodeIfNecessary {
        if (config.get('Bitbucket', false)) {
            doNotifyBitbucket();
        }
        if (config.containsKey('Mail')) {
            doNotifyPerMail(config['Mail']);
        }
    }
}


protected void printDeprecationWarning(String deprecatedFunction, String replacement) {
    echo "┳┻|\n┻┳|\n┳┻| _     HEADS UP! The ${deprecatedFunction} function in your Jenkinsfile is deprecated.\n┻┳| •.•)\n┳┻|⊂ﾉ     Please use ${replacement} instead.\n┻┳|\n┳┻|"
}

/**
 * Send an email.
 */
public void sendMail(String from, String to, String subject, String body) {
    echo "from: $from to: $to subject: $subject body: $body"
    mail bcc: '', body: "$body", cc: '', from: "$from", replyTo: '', subject: "$subject", to: "$to"
}

/**
 * Asks if you want to continue with the deployment to production.
 * It is advisable to call this function outside of a 'node{' block and to open a new node block for the deployment.
 * This approach prevents Jenkins from being blocked. However, the previous git checkout and build will be lost.
 * This is not an issue if the only requirement is to deploy an abn docker image to production.
 * If you need a gitTag, you should store the commit hash from the old node, and check it out in the new one.
 *
 * @param time The maximum waiting time
 * @return true if you want to continue with deployment to production. false otherwise
 */
public boolean requestPrdDeploymentDecision(int time = 2) {
    boolean result = false
    stage('PRD deployment decision') {
        try {
            timeout(time: time, unit: 'HOURS') {
                def deploymentDecision = input(message: 'User input required',
                        parameters: [choice(
                                name: 'Proceed with deployment to PROD ?',
                                choices: 'no\nyes',
                                description: 'Choose "yes" if you want to deploy this build to PROD.')
                        ]
                )
                if (deploymentDecision == 'yes') {
                    println "Deploy to prd"
                    result = true
                } else {
                    println "Skipping deployment to prd"
                }
            }
        } catch (ignored) {
            println "Timout reached. Skipping deployment decision"
        }
        return result
    }
}
