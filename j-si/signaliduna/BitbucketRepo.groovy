#!/usr/bin/env groovy
package de.signaliduna

/**
 * Data class holding information about the Bitbucket Project
 */
class BitbucketRepo {

    String projectName
    String repoName

    BitbucketRepo(String projectName, String repoName) {
        this.projectName = projectName
        this.repoName = repoName
    }
}