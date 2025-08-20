package de.signaliduna

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

import static org.assertj.core.api.BDDAssertions.assertThatExceptionOfType
import static org.assertj.core.api.BDDAssertions.then

class AdditionalTestsystemsTest extends BasePipelineTest {

    Script objectUnderTest

    @BeforeEach
    void setUp() {
        super.setUp()
        Script si_openshift = loadScript("vars/si_openshift.groovy")
        objectUnderTest = loadScript("vars/si_additional_testsystems.groovy")
        objectUnderTest.getBinding().setVariable("si_openshift", si_openshift)
    }

    @ParameterizedTest
    @CsvSource([
        "u123456/StagingAreaWriterImpljava-1675077171561,lvi,u123456/StagingAreaWrite", // Bitbucket generated branch
        "feature/myBranch-lvi, lvi, feature/myBranch",
        "feature/myVeryLongNamedSampleBranch-lvi, lvi, feature/myVeryLongNamedS",
        "feature/myVeryLongNamedSampleBranch-sims, lvi, feature/myVeryLongNamedS",
        "feature/myVeryLongNamedSampleBranch-sims, sims, feature/myVeryLongNamed",
        "feature/AddAdminUser-lvi, sims, feature/AddAdminUser-lv",
        "fix/noBase64PartnerId, lvi, fix/noBase64PartnerI",
        "addPaTestSys-sims, sims, addPaTestSys",
        "addPaTestSys-lvi, lvi, addPaTestSys",
        "develop-lvi, lvi, develop",
        "develop, lvi, develop",
        "develop-lvi-test-lvi, lvi, develop-lvi-test", // Suffix in the middle is ignored
        "renovate/AddAdminUser-lvi, lvi, renovate/AddAdminUser",
        "renovate/sdaseversion, lvi, renovate/sdaseversion",
        "renovate/gradle-7.x, lvi, renovate/gradle-7.x",
        "renovate/org.sonarqube-3.x, lvi, renovate/org.sonarqube-3.",
        "renovate/com.sdase.service-legally-compliant-document-delivery-streaming-model-4.x, lvi, renovate/com.sdase.servic",
        // Last '-' is cut of by si_openshift.filterBranchName(), therefore accept 19 character long branch name ("org-mockito-mockito-" --> "org-mockito-mockito")
        "renovate/org.mockito-mockito-inline-4.x, lvi, renovate/org.mockito-mock",
        "renovate/com.amazonaws-aws-java-sdk-s3-1.x, lvi, renovate/com.amazonaws-aw",
        "renovate/prod.docker.system.local-sda-centos7-jre11-161.x, lvi, renovate/prod.docker.syst",
        "renovate/prod.docker.system.local-sda-centos7-opa0-147.x, lvi, renovate/prod.docker.syst"])
    void filterTestSystemBranchName(String branchName, String testSystemName, String expected) {
        // Arrange + Act
        String result = objectUnderTest.filterTestSystemSuffixFromBranchName(branchName, testSystemName)

        // Assert
        then(result).isEqualTo(expected)
    }

    @Test
    void filterTestSystemBranchNameTooLongTestSystemName() {
        // Arrange + Act
        assertThatExceptionOfType(Exception.class).isThrownBy(() ->
                objectUnderTest.filterTestSystemSuffixFromBranchName("myBranch", "extraordinarylongtestsystem")
        )
    }

    @Test
    void removeMergedBranches() {
        helper.addShMock("git branch -r | sed 's/origin\\///g'") { script ->
            return [stdout: """ develop
                develop-lvi
                master
                feature/AddAdminUser
                feature/AddAdminUser-lvi
                feature/DEF-4711-lvi
                feature/ABC-123-merge-more
                feature/ABC-123-merge-mo-nkv
                feature/ABC-123-merge-mo-lvi
                feature/ABC-123-merge-m-sims
                feature/ABC-45-merged-br-nkv
                feature/ABC-45-merged-br-ska
                feature/ABC-45-merged-b-sims
                feature/ABC-456-more-systems
                feature/ABC-456-more-sys-lvi
                feature/ABC-456-more-sys-nkv
                feature/ABC-456-more-sy-sims""", exitValue: 0]
        }
        objectUnderTest.removeMergedBranches(["nkv", "lvi", "sims"])

        def executedScripts = helper.callStack.findAll { call -> call.methodName == 'sh' }
                .collect { it -> it.getArgs()[0] }
        // Delete all manged test system branches that have no corresponding used branch (e.g. base branch was merged)
        then(executedScripts).contains('git push origin --delete feature/DEF-4711-lvi')
        then(executedScripts).contains('git push origin --delete feature/ABC-45-merged-br-nkv',
                'git push origin --delete feature/ABC-45-merged-b-sims')
        // develop and master branches should not be deleted
        then(executedScripts).doesNotContain('git push origin --delete develop',
                'git push origin --delete develop-lvi',
                'git push origin --delete master')
        // Only delete test system branches for managed testsystems (ska is out of scope)
        then(executedScripts).doesNotContain('git push origin --delete feature/ABC-45-merged-br-ska')
        // Do not delete testsystems that have a corresponding branch without testsystem suffix
        then(executedScripts).doesNotContain('git push origin --delete feature/AddAdminUser',
                'git push origin --delete feature/AddAdminUser-lvi')
        then(executedScripts).doesNotContain('git push origin --delete feature/ABC-123-merge-more',
                'git push origin --delete feature/ABC-123-merge-mo-nkv',
                'git push origin --delete feature/ABC-123-merge-mo-lvi',
                'git push origin --delete feature/ABC-123-merge-mo-sims')
        then(executedScripts).doesNotContain('git push origin --delete feature/ABC-456-more-systems',
                'git push origin --delete feature/ABC-456-more-sys-lvi',
                'git push origin --delete feature/ABC-456-more-sys-nkv',
                'git push origin --delete feature/ABC-456-more-sy-sims')
    }
}
