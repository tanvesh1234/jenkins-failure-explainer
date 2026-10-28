// Job DSL: creates the demo pipelines. Every one of them fails on purpose and calls
// explainFailure() in post { failure }.
// (Plain string concatenation on purpose: this file is loaded through JCasC.)

folder('demo') {
    displayName('Demo: failing builds')
    description('Each job fails in a different way. Run one, then read the explanation at the ' +
            'end of the console log, on the build page, and in the archived failure-explanation.md.')
}

String pipelineFor(String stageName, String stepScript) {
    return """@Library('failure-explainer') _

pipeline {
    agent any
    options { buildDiscarder(logRotator(numToKeepStr: '10')) }
    stages {
        stage('Checkout') {
            steps { echo 'Checking out sources (simulated)' }
        }
        stage('""" + stageName + """') {
            steps { sh '""" + stepScript + """' }
        }
    }
    post {
        failure {
            explainFailure()
        }
    }
}
"""
}

// Recorded failures, replayed inside a real sh step (no toolchains needed)
Map replayed = [
        'maven-compile-error': ['Build', 'Java: a refactor removed a method two classes still call'],
        'npm-eresolve'       : ['Install', 'Node: peer dependency conflict after a React upgrade'],
        'pytest-failures'    : ['Test', 'Python: two tests fail after a rounding change (log also leaks secrets)'],
        'java-oom'           : ['Integration Tests', 'Gradle: test loads a full year of rows and runs out of heap'],
        'docker-daemon-down' : ['Docker Image', 'Docker daemon not reachable from the agent'],
]

replayed.each { String name, List meta ->
    pipelineJob('demo/' + name) {
        description(meta[1] + '  (replays demo/logs/' + name + '.log)')
        definition {
            cps {
                script(pipelineFor(meta[0], '/opt/demo/replay.sh ' + name))
                sandbox(true)
            }
        }
    }
}

// Real failures, nothing simulated
pipelineJob('demo/real-missing-tool') {
    description('Real failure: the agent has no kubectl installed.')
    definition {
        cps {
            script(pipelineFor('Deploy', 'kubectl apply -f k8s/deployment.yaml'))
            sandbox(true)
        }
    }
}

pipelineJob('demo/real-network-error') {
    description('Real failure: the build calls an internal host that does not resolve.')
    definition {
        cps {
            script(pipelineFor('Publish', 'curl -fsS https://nexus.acme.internal/repository/releases/'))
            sandbox(true)
        }
    }
}
