/**
 * Build frontend.
 * Deletes all current node modules and build frontend by performing npm install.
 * Node library v9.2.0 is used.
 */
public void build(String path, String project) {
    dir(path) {
        sh """
        export PATH=/srv/dev/node-v9.2.0-linux-x64/bin:\\$PATH
        rm -rf ./node_modules/
        npm install
        ./node_modules/@angular/cli/bin/ng build --prod $project
    """
    }
}

/**
 * Performs all frontend tests by calling ng test
 * Performs checkstyle by calling ng lint
 */
public void check(String path, String project) {
    test(path, project)
    checkstyle(path, project)
}

private void test(String path, String project) {
    dir(path) {
        sh """
            export CHROME_BIN=/opt/chromium/chromium-latest-linux/latest/chrome
            export PATH=/srv/dev/node-v9.2.0-linux-x64/bin:\\$PATH
            ./node_modules/@angular/cli/bin/ng test $project
        """
    }
}

private void checkstyle(String path, String project) {
    dir (path) {
        sh """
            export PATH=/srv/dev/node-v9.2.0-linux-x64/bin:\\$PATH
            ./node_modules/@angular/cli/bin/ng lint $project --force --format checkstyle > checkstyle-result-ts.xml
        """
    }
}