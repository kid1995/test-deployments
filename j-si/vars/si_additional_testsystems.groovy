import de.signaliduna.TargetSegment

void createBranches(TargetSegment targetSegment = TargetSegment.tst, List configFolders = ['openshift']) {
    if (targetSegment == TargetSegment.prd) {
        throw new Exception("TargetSegment must be tst or abn!")
    }
    def leadingConfigFolder = configFolders[0]
    echo "Search for test systems in: $leadingConfigFolder"
    def testSystems = findFiles(glob: "$leadingConfigFolder/*-${targetSegment}.properties.yml")
            .collect { it -> it.name.replace("-${targetSegment}.properties.yml", "") }
    removeMergedBranches(testSystems)
    testSystems.each { createBranch(it, targetSegment, configFolders) }
}

void createBranch(String testSystemName, TargetSegment targetSegment = TargetSegment.tst, List configFolders = ['openshift']) {
    if (targetSegment == TargetSegment.prd) {
        throw new Exception("TargetSegment must be tst or abn!")
    }
    def sourceBranchName = env.BRANCH_NAME
    def filteredSourceBranchName = filterTestSystemSuffixFromBranchName(sourceBranchName, testSystemName)
    def testSystemBranchName = "$filteredSourceBranchName-$testSystemName"
    sh """
    set +e 
    git checkout ${sourceBranchName}
    get fetch --all
    git push origin --delete ${testSystemBranchName} 
    git branch -D ${testSystemBranchName}
    set -e

    git branch ${testSystemBranchName}
    git checkout ${testSystemBranchName}
    """

    configFolders.each {
        sh """
        mv ${it}/${testSystemName}-${targetSegment}.properties.yml ${it}/${targetSegment}.properties.yml
        rm -f ${it}/*-${targetSegment}.properties.yml
        """
    }

    sh """
    git add .
    git commit -m 'activate config for ${testSystemName} in ${testSystemBranchName}'
    git push --set-upstream origin ${testSystemBranchName}
    git checkout ${sourceBranchName}
    """
}

private void removeMergedBranches(def testSystems) {
    def openBranches = sh(
            returnStdout: true,
            script: "git branch -r | sed 's/origin\\///g'"
    ).split("\n").collect { it.trim() }

    def testSystemBranches = openBranches.findAll { testSystems.any { testSystem -> it.endsWith("-$testSystem") } }
    def usedBranches = openBranches.findAll { testSystems.every { testSystem -> !it.endsWith("-$testSystem") } }
    def mergedBranches = [testSystemBranches, testSystems].combinations()
            .findAll { combination ->
                def testSystemBranch = combination[0]
                def testSystem = combination[1]
                if (!testSystemBranch.endsWith("-$testSystem")) {
                    return false
                }
                def usedBranchesWithCutInTestSystemSuffixLength = usedBranches
                        .findAll { usedBranch -> usedBranch.lastIndexOf("-") + 1 + testSystem.length() < usedBranch.length() }
                        .collect { usedBranch -> filterTestSystemSuffixFromBranchName(usedBranch, testSystem) }
                def filteredBranchName = filterTestSystemSuffixFromBranchName(testSystemBranch, testSystem)
                return !usedBranchesWithCutInTestSystemSuffixLength.contains(filteredBranchName)
        }.collect { combination-> combination[0] }
    echo "Found test system: $testSystems"
    echo "Delete merged test system branches: $mergedBranches"
    mergedBranches?.each { sh "git push origin --delete ${it}" }
}

private String filterTestSystemSuffixFromBranchName(String branchName, String testSystemName) {
    def maxLengthOfBranchName = 20
    if (testSystemName.length() > maxLengthOfBranchName - 1) {
        throw new Exception("Test system name is too long (${testSystemName.length()}) characters with max $maxLengthOfBranchName characters)! " +
                "There must be at least one character left for the branch name.")
    }
    def branchNameWithoutSuffix = branchName.replaceAll("-$testSystemName\$", "")
    def lastBranchNamePartLength = branchNameWithoutSuffix.substring(branchName.lastIndexOf("/") + 1).length()
    def testSystemSuffixLength = testSystemName.length() + 1

    def isTestSystemNameInMaxOpenShiftNameLength = lastBranchNamePartLength + testSystemSuffixLength <= maxLengthOfBranchName
    if (isTestSystemNameInMaxOpenShiftNameLength) {
        return branchNameWithoutSuffix
    }

    def diff = branchNameWithoutSuffix.length() - lastBranchNamePartLength
    return branchNameWithoutSuffix.substring(0, (maxLengthOfBranchName + diff) - testSystemSuffixLength)
}