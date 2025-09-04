
/**
 * ELPA Git Operations for Feature-based Deployments
 * Handles creation and management of feature deployments based on Jira tickets
 */

def createFeatureDeployment(Map config = [:]) {
    /**
     * Creates a feature deployment for a specific service and Jira ticket
     * @param config.service - Service name (e.g., 'hint-service', 'ppa-service')
     * @param config.jiraTicket - Jira ticket number (e.g., 'ELPA-1234')
     * @param config.environment - Target environment (default: 'dev')
     * @param config.imageTag - Docker image tag to deploy
     * @param config.baseBranch - Base branch to create from (default: 'main')
     * @param config.autoMerge - Auto-create PR and merge (default: false)
     */
    
    def service = config.service ?: error("Service name is required")
    def jiraTicket = config.jiraTicket ?: error("Jira ticket is required")
    def environment = config.environment ?: 'dev'
    def imageTag = config.imageTag ?: 'latest'
    def baseBranch = config.baseBranch ?: 'main'
    def autoMerge = config.autoMerge ?: false
    
    // Normalize Jira ticket (convert to lowercase for folder naming)
    def ticketId = jiraTicket.toLowerCase().replaceAll('[^a-z0-9-]', '-')
    
    echo "Creating feature deployment for ${service} - ${jiraTicket}"
    
    return {
        stage("Create Feature Deployment: ${jiraTicket}") {
            dir('deployment-repo') {
                // Checkout deployment repository
                git branch: baseBranch, 
                    url: env.DEPLOYMENT_REPO_URL ?: 'https://git.company.com/elpa/deployments.git',
                    credentialsId: 'git-credentials'
                
                // Create feature branch
                def featureBranch = "feature/${ticketId}-${service}"
                sh """
                    git checkout -b ${featureBranch} || git checkout ${featureBranch}
                    git pull origin ${baseBranch} --rebase
                """
                
                // Create feature deployment
                def sourceDir = "envs/${environment}/${service}"
                def targetDir = "envs/${environment}/${service}-${ticketId}"
                
                sh """
                    # Check if source exists
                    if [ ! -d "${sourceDir}" ]; then
                        echo "Error: Source directory ${sourceDir} does not exist"
                        exit 1
                    fi
                    
                    # Remove existing feature deployment if it exists
                    rm -rf ${targetDir}
                    
                    # Copy service configuration
                    cp -r ${sourceDir} ${targetDir}
                    
                    # Update kustomization.yaml with Jira ticket suffix
                    cd ${targetDir}
                    
                    # Update nameSuffix
                    if grep -q 'nameSuffix:' kustomization.yaml; then
                        sed -i "s/nameSuffix: .*/nameSuffix: \"-${service##*-}-${ticketId}\"/" kustomization.yaml
                    else
                        echo "nameSuffix: \"-${service##*-}-${ticketId}\"" >> kustomization.yaml
                    fi
                    
                    # Update namePrefix to include environment and ticket
                    sed -i "s/namePrefix: .*/namePrefix: \"${environment}-${ticketId}-\"/" kustomization.yaml
                    
                    # Update image tag
                    sed -i "s/newTag: .*/newTag: ${imageTag}/" kustomization.yaml
                    
                    # Add feature label
                    if ! grep -q 'commonLabels:' kustomization.yaml; then
                        echo "" >> kustomization.yaml
                        echo "commonLabels:" >> kustomization.yaml
                    fi
                    echo "  feature: ${ticketId}" >> kustomization.yaml
                    echo "  jira-ticket: ${jiraTicket}" >> kustomization.yaml
                """
                
                // Update parent kustomization to include feature deployment
                updateParentKustomization(environment, service, ticketId)
                
                // Commit changes
                sh """
                    git add .
                    git commit -m "feat(${jiraTicket}): Create feature deployment for ${service}
                    
                    - Created feature deployment in ${targetDir}
                    - Updated nameSuffix and namePrefix with ticket ID
                    - Set image tag to ${imageTag}
                    - Added feature labels for tracking
                    
                    Jira: ${jiraTicket}" || echo "No changes to commit"
                """
                
                // Push changes
                sh "git push origin ${featureBranch} --force-with-lease"
                
                // Create Pull Request if needed
                if (autoMerge) {
                    createPullRequest([
                        title: "[${jiraTicket}] Feature deployment for ${service}",
                        sourceBranch: featureBranch,
                        targetBranch: baseBranch,
                        description: "Automated feature deployment for Jira ticket ${jiraTicket}",
                        labels: ['feature-deployment', 'automated']
                    ])
                }
                
                return [
                    status: 'success',
                    deploymentPath: targetDir,
                    branch: featureBranch,
                    namespace: "elpa4",
                    deploymentName: "${environment}-${ticketId}-app-${service##*-}-${ticketId}"
                ]
            }
        }
    }()
}

def removeFeatureDeployment(Map config = [:]) {
    /**
     * Removes a feature deployment
     * @param config.service - Service name
     * @param config.jiraTicket - Jira ticket number
     * @param config.environment - Target environment
     * @param config.createPR - Create PR for removal (default: true)
     */
    
    def service = config.service ?: error("Service name is required")
    def jiraTicket = config.jiraTicket ?: error("Jira ticket is required")
    def environment = config.environment ?: 'dev'
    def createPR = config.createPR ?: true
    
    def ticketId = jiraTicket.toLowerCase().replaceAll('[^a-z0-9-]', '-')
    
    return {
        stage("Remove Feature Deployment: ${jiraTicket}") {
            dir('deployment-repo') {
                git branch: 'main', 
                    url: env.DEPLOYMENT_REPO_URL,
                    credentialsId: 'git-credentials'
                
                def removalBranch = "cleanup/${ticketId}-${service}"
                sh "git checkout -b ${removalBranch}"
                
                def targetDir = "envs/${environment}/${service}-${ticketId}"
                
                // First, delete from Kubernetes if it exists
                sh """
                    # Try to delete the deployment from cluster
                    kubectl delete -k ${targetDir} --ignore-not-found=true || true
                """
                
                // Remove feature deployment directory
                sh """
                    if [ -d "${targetDir}" ]; then
                        rm -rf ${targetDir}
                        echo "Removed feature deployment: ${targetDir}"
                    else
                        echo "Feature deployment not found: ${targetDir}"
                    fi
                """
                
                // Update parent kustomization
                removeFromParentKustomization(environment, service, ticketId)
                
                // Commit and push
                sh """
                    git add .
                    git commit -m "cleanup(${jiraTicket}): Remove feature deployment for ${service}
                    
                    - Removed feature deployment from ${targetDir}
                    - Updated parent kustomization.yaml
                    - Cleaned up Kubernetes resources
                    
                    Jira: ${jiraTicket}" || echo "No changes to commit"
                    
                    git push origin ${removalBranch}
                """
                
                if (createPR) {
                    createPullRequest([
                        title: "[${jiraTicket}] Remove feature deployment for ${service}",
                        sourceBranch: removalBranch,
                        targetBranch: 'main',
                        description: "Cleanup feature deployment for completed ticket ${jiraTicket}"
                    ])
                }
            }
        }
    }()
}

def deployFeature(Map config = [:]) {
    /**
     * Deploys a feature to Kubernetes
     * @param config.service - Service name
     * @param config.jiraTicket - Jira ticket number
     * @param config.environment - Target environment
     * @param config.dryRun - Perform dry run only (default: false)
     */
    
    def service = config.service
    def jiraTicket = config.jiraTicket
    def environment = config.environment ?: 'dev'
    def dryRun = config.dryRun ?: false
    
    def ticketId = jiraTicket.toLowerCase().replaceAll('[^a-z0-9-]', '-')
    def deploymentPath = "envs/${environment}/${service}-${ticketId}"
    
    return {
        stage("Deploy Feature: ${jiraTicket}") {
            dir('deployment-repo') {
                git branch: 'main', 
                    url: env.DEPLOYMENT_REPO_URL,
                    credentialsId: 'git-credentials'
                
                def dryRunFlag = dryRun ? '--dry-run=client' : ''
                
                sh """
                    # Apply the feature deployment
                    kubectl apply -k ${deploymentPath} ${dryRunFlag}
                    
                    if [ -z "${dryRunFlag}" ]; then
                        # Wait for rollout to complete
                        kubectl rollout status deployment/${environment}-${ticketId}-app-${service##*-}-${ticketId} \
                            -n elpa4 \
                            --timeout=5m || true
                        
                        # Show deployment status
                        kubectl get deployments,services,pods \
                            -n elpa4 \
                            -l feature=${ticketId} \
                            -o wide
                    fi
                """
                
                // Get deployment URL if service is exposed
                def deploymentUrl = getFeatureDeploymentUrl(environment, service, ticketId)
                
                return [
                    status: 'deployed',
                    url: deploymentUrl,
                    namespace: 'elpa4',
                    deployment: "${environment}-${ticketId}-app-${service##*-}-${ticketId}"
                ]
            }
        }
    }()
}

def updateFeatureImage(Map config = [:]) {
    /**
     * Updates the image tag for a feature deployment
     * @param config.service - Service name
     * @param config.jiraTicket - Jira ticket number
     * @param config.environment - Target environment
     * @param config.newTag - New image tag
     * @param config.autoDeploy - Automatically deploy after update (default: true)
     */
    
    def service = config.service
    def jiraTicket = config.jiraTicket
    def environment = config.environment ?: 'dev'
    def newTag = config.newTag ?: error("New image tag is required")
    def autoDeploy = config.autoDeploy ?: true
    
    def ticketId = jiraTicket.toLowerCase().replaceAll('[^a-z0-9-]', '-')
    
    return {
        stage("Update Feature Image: ${jiraTicket}") {
            dir('deployment-repo') {
                git branch: 'main', 
                    url: env.DEPLOYMENT_REPO_URL,
                    credentialsId: 'git-credentials'
                
                def updateBranch = "update/${ticketId}-${service}-${env.BUILD_NUMBER}"
                sh "git checkout -b ${updateBranch}"
                
                def targetDir = "envs/${environment}/${service}-${ticketId}"
                
                sh """
                    cd ${targetDir}
                    # Update image tag
                    sed -i "s/newTag: .*/newTag: ${newTag}/" kustomization.yaml
                    
                    # Add/update annotation for tracking
                    if ! grep -q 'commonAnnotations:' kustomization.yaml; then
                        echo "" >> kustomization.yaml
                        echo "commonAnnotations:" >> kustomization.yaml
                    fi
                    echo "  last-updated: \"\$(date -Iseconds)\"" >> kustomization.yaml
                    echo "  updated-by: \"jenkins-${env.BUILD_NUMBER}\"" >> kustomization.yaml
                """
                
                sh """
                    git add .
                    git commit -m "chore(${jiraTicket}): Update ${service} image to ${newTag}
                    
                    - Updated image tag from previous to ${newTag}
                    - Build: ${env.BUILD_NUMBER}
                    
                    Jira: ${jiraTicket}"
                    
                    git push origin ${updateBranch}
                """
                
                // Fast-forward merge for image updates
                sh """
                    git checkout main
                    git pull origin main
                    git merge ${updateBranch} --ff-only
                    git push origin main
                """
                
                if (autoDeploy) {
                    deployFeature(config)
                }
            }
        }
    }()
}

// Helper function to update parent kustomization
private def updateParentKustomization(environment, service, ticketId) {
    sh """
        cd envs/${environment}
        
        # Check if kustomization.yaml exists
        if [ ! -f kustomization.yaml ]; then
            echo "Creating kustomization.yaml in envs/${environment}"
            cat > kustomization.yaml <<EOF
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - ${service}-${ticketId}
EOF
        else
            # Add new resource if not already present
            if ! grep -q "${service}-${ticketId}" kustomization.yaml; then
                # Check if resources section exists
                if grep -q "^resources:" kustomization.yaml; then
                    # Add under existing resources
                    sed -i "/^resources:/a\\  - ${service}-${ticketId}" kustomization.yaml
                else
                    # Add resources section
                    echo "" >> kustomization.yaml
                    echo "resources:" >> kustomization.yaml
                    echo "  - ${service}-${ticketId}" >> kustomization.yaml
                fi
            fi
        fi
    """
}

// Helper function to remove from parent kustomization
private def removeFromParentKustomization(environment, service, ticketId) {
    sh """
        cd envs/${environment}
        
        if [ -f kustomization.yaml ]; then
            # Remove the resource line
            sed -i "/${service}-${ticketId}/d" kustomization.yaml
        fi
    """
}

// Helper function to get deployment URL
private def getFeatureDeploymentUrl(environment, service, ticketId) {
    def url = sh(
        script: """
            kubectl get ingress \
                -n elpa4 \
                -l feature=${ticketId},app=${service} \
                -o jsonpath='{.items[0].spec.rules[0].host}' 2>/dev/null || echo ""
        """,
        returnStdout: true
    ).trim()
    
    return url ?: "No ingress found - service may be internal only"
}

// Helper function to create pull request (placeholder - implement based on your Git provider)
private def createPullRequest(Map config) {
    echo """
    Creating Pull Request:
    Title: ${config.title}
    Source: ${config.sourceBranch}
    Target: ${config.targetBranch}
    Description: ${config.description}
    """
    
    // Implement based on your Git provider (GitHub, GitLab, Bitbucket, etc.)
    // Example for GitLab:
    // sh """
    //     curl -X POST \
    //         -H "PRIVATE-TOKEN: ${env.GITLAB_TOKEN}" \
    //         "${env.GITLAB_API_URL}/projects/${env.PROJECT_ID}/merge_requests" \
    //         -d "source_branch=${config.sourceBranch}" \
    //         -d "target_branch=${config.targetBranch}" \
    //         -d "title=${config.title}" \
    //         -d "description=${config.description}"
    // """
}

// Convenience method to list all feature deployments
def listFeatureDeployments(Map config = [:]) {
    def environment = config.environment ?: 'dev'
    
    return {
        stage("List Feature Deployments") {
            sh """
                echo "Feature Deployments in ${environment}:"
                kubectl get deployments,services \
                    -n elpa4 \
                    -l environment=${environment} \
                    -o custom-columns=NAME:.metadata.name,FEATURE:.metadata.labels.feature,JIRA:.metadata.labels.jira-ticket,CREATED:.metadata.creationTimestamp \
                    | grep -E "feature|jira" || echo "No feature deployments found"
            """
        }
    }()
}

// Batch operations for multiple features
def deployMultipleFeatures(Map config = [:]) {
    /**
     * Deploy multiple features at once
     * @param config.features - List of maps with service and jiraTicket
     * @param config.environment - Target environment
     * @param config.parallel - Deploy in parallel (default: false)
     */
    
    def features = config.features ?: []
    def environment = config.environment ?: 'dev'
    def runParallel = config.parallel ?: false
    
    if (features.isEmpty()) {
        error("No features provided for deployment")
    }
    
    def deploymentStages = [:]
    
    features.each { feature ->
        def stageName = "Deploy ${feature.jiraTicket}"
        deploymentStages[stageName] = {
            createFeatureDeployment([
                service: feature.service,
                jiraTicket: feature.jiraTicket,
                environment: environment,
                imageTag: feature.imageTag ?: 'latest'
            ])
            
            deployFeature([
                service: feature.service,
                jiraTicket: feature.jiraTicket,
                environment: environment
            ])
        }
    }
    
    if (runParallel) {
        parallel deploymentStages
    } else {
        deploymentStages.each { name, closure ->
            closure()
        }
    }
}

return this