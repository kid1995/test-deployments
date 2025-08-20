#!/usr/bin/env groovy
package de.signaliduna

/**
 * Data class holding information about the PullRequest a build is related to
 */
class BitbucketComment {

    String text
    List<String> tasks = []

    BitbucketComment(String text) {
        this.text = text
    }

    public void addTask(String task) {
        this.tasks.add(task)
    }
}