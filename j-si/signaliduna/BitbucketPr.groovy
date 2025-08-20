#!/usr/bin/env groovy
package de.signaliduna

/**
 * Data class holding information about the PullRequest a build is related to
 */
class BitbucketPr {

    String id
    String target

    BitbucketPr(String id, String target) {
        this.id = id
        this.target = target
    }
}