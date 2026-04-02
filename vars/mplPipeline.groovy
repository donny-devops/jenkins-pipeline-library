/**
 * mplPipeline — Modular Pipeline Library (MPL) entry point
 *
 * Runs a fully modular pipeline where each stage is an independent Groovy
 * module. Projects override individual modules by placing files at:
 *
 *   .jenkins/modules/<ModuleName>.groovy
 *
 * Library defaults live in resources/mpl/modules/<ModuleName>.groovy.
 * Modules are resolved at runtime — no rebuild needed to override.
 *
 * ── Jenkinsfile usage ───────────────────────────────────────────────────────
 *
 *   @Library('pipeline-library') _
 *
 *   mplPipeline(
 *     // Global config inherited by every module.
 *     agent    : 'docker',
 *     registry : 'registry.example.com/myteam',
 *     namespace: 'staging',
 *
 *     // Module-specific config — merged with global config inside each module.
 *     modules: [
 *       Build       : [tool: 'maven', goals: 'clean package'],
 *       Test        : [tool: 'maven', coverageMin: 80],
 *       SecurityScan: [failOnCritical: true, skipSast: false],
 *       Docker      : [image: 'myapp/api', dockerfile: 'Dockerfile'],
 *       Deploy      : [type: 'helm', chart: 'myapp', release: 'api-staging'],
 *     ],
 *
 *     // Stage ordering override (default shown).
 *     stageOrder: ['Build', 'Test', 'SecurityScan', 'Docker', 'Deploy'],
 *
 *     // Disable specific stages without removing them from stageOrder.
 *     skip: ['SecurityScan'],
 *
 *     // Per-stage condition closures — stage runs only when closure returns true.
 *     conditions: [
 *       Deploy: { env.BRANCH_NAME ==~ /^(main|release\/.*)$/ },
 *     ],
 *   )
 */

def call(Map cfg) {
    def mpl = new com.pipeline.mpl.MPLManager(this, cfg)

    pipeline {
        agent { label cfg.get('agent', 'any') }

        options {
            timestamps()
            timeout(time: cfg.get('pipelineTimeoutMinutes', 60), unit: 'MINUTES')
            buildDiscarder(logRotator(numToKeepStr: '30'))
            disableConcurrentBuilds()
        }

        stages {
            stage('Pipeline Init') {
                steps {
                    script {
                        mpl.init()
                        echo "MPL resolved module sources:\n${mpl.moduleSourceReport()}"
                    }
                }
            }

            stage('Modules') {
                steps {
                    script {
                        mpl.runAll()
                    }
                }
            }
        }

        post {
            always {
                script { mpl.runPost() }
            }
            success {
                script { mpl.notify('SUCCESS') }
            }
            failure {
                script { mpl.notify('FAILURE') }
            }
        }
    }
}
