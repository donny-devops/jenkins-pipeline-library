/**
 * threeTierDeploy — 3-Tier App Deployer
 *
 * Orchestrates build → Dockerization → Kubernetes deployment for frontend,
 * backend, and database tiers. Tier builds and Docker pushes run in parallel;
 * Kubernetes deployments are sequential (database → backend → frontend) to
 * respect dependency ordering.
 *
 * ── Jenkinsfile usage ───────────────────────────────────────────────────────
 *
 *   @Library('pipeline-library') _
 *
 *   threeTierDeploy(
 *     registry    : 'registry.example.com/myteam',
 *     imageTag    : env.BUILD_NUMBER,
 *     kubeconfig  : 'my-kubeconfig-credential',
 *     namespace   : 'production',
 *     helmTimeout : '10m',
 *
 *     tiers: [
 *       database: [
 *         enabled  : true,
 *         type     : 'helm',           // helm | manifest | skip
 *         chart    : 'bitnami/postgresql',
 *         release  : 'myapp-postgres',
 *         values   : 'k8s/db-values.yaml',
 *         waitFor  : true,             // block until rollout is healthy
 *       ],
 *       backend: [
 *         enabled    : true,
 *         buildTool  : 'maven',        // maven | gradle | npm | python | docker-only
 *         image      : 'myapp/backend',
 *         context    : 'backend/',
 *         dockerfile : 'backend/Dockerfile',
 *         k8sType    : 'manifest',     // helm | manifest
 *         k8sDir     : 'k8s/backend/',
 *         healthCheck: 'http://backend-svc/health',
 *       ],
 *       frontend: [
 *         enabled    : true,
 *         buildTool  : 'npm',
 *         image      : 'myapp/frontend',
 *         context    : 'frontend/',
 *         dockerfile : 'frontend/Dockerfile',
 *         k8sType    : 'manifest',
 *         k8sDir     : 'k8s/frontend/',
 *       ],
 *     ],
 *
 *     // Optional hooks — closures executed at lifecycle points
 *     onBuildSuccess : { tier -> echo "Built ${tier}" },
 *     onDeploySuccess: { tier -> slackSend message: "${tier} deployed" },
 *     onFailure      : { tier, err -> pagerdutyAlert(err) },
 *   )
 */

def call(Map cfg) {
    def deployer = new com.pipeline.deployer.TierDeployer(this, cfg)

    pipeline {
        agent { label cfg.get('agent', 'any') }

        options {
            timestamps()
            timeout(time: cfg.get('pipelineTimeoutMinutes', 60), unit: 'MINUTES')
            buildDiscarder(logRotator(numToKeepStr: '20'))
            disableConcurrentBuilds(abortPrevious: true)
        }

        environment {
            REGISTRY      = cfg.registry ?: error('threeTierDeploy: registry is required')
            IMAGE_TAG     = cfg.get('imageTag', env.BUILD_NUMBER)
            KUBE_NS       = cfg.get('namespace', 'default')
            HELM_TIMEOUT  = cfg.get('helmTimeout', '5m')
        }

        stages {

            // ── 0. Checkout ────────────────────────────────────────────────
            stage('Checkout') {
                steps {
                    script {
                        deployer.checkout()
                    }
                }
            }

            // ── 1. Build (parallel per enabled tier) ──────────────────────
            stage('Build') {
                steps {
                    script {
                        def buildBranches = deployer.buildBranches()
                        if (buildBranches) {
                            parallel buildBranches
                        } else {
                            echo 'No build steps configured — skipping.'
                        }
                    }
                }
            }

            // ── 2. Test (parallel per enabled tier) ───────────────────────
            stage('Test') {
                steps {
                    script {
                        def testBranches = deployer.testBranches()
                        if (testBranches) {
                            parallel testBranches
                        } else {
                            echo 'No test steps configured — skipping.'
                        }
                    }
                }
                post {
                    always {
                        script { deployer.collectTestReports() }
                    }
                }
            }

            // ── 3. Dockerize (parallel per enabled tier) ──────────────────
            stage('Dockerize') {
                steps {
                    script {
                        def dockerBranches = deployer.dockerBranches()
                        if (dockerBranches) {
                            parallel dockerBranches
                        } else {
                            echo 'No Docker builds configured — skipping.'
                        }
                    }
                }
            }

            // ── 4. Deploy — sequential: database → backend → frontend ─────
            stage('Deploy: Database') {
                when {
                    expression { deployer.tierEnabled('database') }
                }
                steps {
                    script { deployer.deployTier('database') }
                }
            }

            stage('Deploy: Backend') {
                when {
                    expression { deployer.tierEnabled('backend') }
                }
                steps {
                    script { deployer.deployTier('backend') }
                }
            }

            stage('Deploy: Frontend') {
                when {
                    expression { deployer.tierEnabled('frontend') }
                }
                steps {
                    script { deployer.deployTier('frontend') }
                }
            }

            // ── 5. Smoke Test ──────────────────────────────────────────────
            stage('Smoke Test') {
                when {
                    expression { cfg.get('runSmokeTests', true) }
                }
                steps {
                    script { deployer.smokeTest() }
                }
            }

        } // stages

        post {
            success {
                script {
                    if (cfg.onDeploySuccess) {
                        cfg.onDeploySuccess.call('all')
                    }
                    deployer.notifySuccess()
                }
            }
            failure {
                script {
                    if (cfg.onFailure) {
                        cfg.onFailure.call('pipeline', currentBuild.description)
                    }
                    deployer.notifyFailure()
                }
            }
            always {
                cleanWs()
            }
        }

    } // pipeline
}
