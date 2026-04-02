package com.pipeline.deployer

/**
 * TierDeployer — orchestrates the full lifecycle of a 3-tier application.
 *
 * Separates pipeline logic from Jenkinsfile DSL so it can be unit-tested
 * independently of a live Jenkins controller.
 */
class TierDeployer implements Serializable {

    private final def steps
    private final Map cfg
    private final DockerBuilder docker
    private final K8sDeployer k8s

    // Canonical tier ordering for sequential deploy.
    static final List<String> DEPLOY_ORDER = ['database', 'backend', 'frontend']

    // Build tool → shell command fragments.
    static final Map<String, Map> BUILD_TOOLS = [
        maven     : [build: 'mvn -B -q clean package -DskipTests', test: 'mvn -B test'],
        gradle    : [build: './gradlew assemble',                  test: './gradlew test'],
        npm       : [build: 'npm ci && npm run build',             test: 'npm test -- --watchAll=false'],
        yarn      : [build: 'yarn install --frozen-lockfile && yarn build', test: 'yarn test --watchAll=false'],
        python    : [build: 'pip install -r requirements.txt',     test: 'pytest --junitxml=test-results/results.xml'],
        'docker-only': [build: '', test: ''],
    ]

    TierDeployer(def steps, Map cfg) {
        this.steps  = steps
        this.cfg    = cfg
        this.docker = new DockerBuilder(steps, cfg)
        this.k8s    = new K8sDeployer(steps, cfg)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    boolean tierEnabled(String name) {
        return cfg.tiers?.get(name)?.get('enabled', false) == true
    }

    private Map tierCfg(String name) {
        return (cfg.tiers?.get(name) ?: [:]) as Map
    }

    // ── Checkout ─────────────────────────────────────────────────────────────

    void checkout() {
        steps.echo "Checking out source…"
        steps.checkout steps.scm
    }

    // ── Build branches (parallel map) ────────────────────────────────────────

    Map buildBranches() {
        def branches = [:]
        ['backend', 'frontend'].each { tier ->
            if (!tierEnabled(tier)) return
            def tcfg = tierCfg(tier)
            def tool = tcfg.get('buildTool', 'docker-only')
            if (tool == 'docker-only') return

            branches["Build: ${tier}"] = {
                steps.stage("Build ${tier.capitalize()}") {
                    steps.dir(tcfg.get('context', tier)) {
                        def cmd = BUILD_TOOLS[tool]?.build
                        if (cmd) {
                            steps.sh cmd
                        }
                    }
                }
                if (cfg.onBuildSuccess) {
                    cfg.onBuildSuccess.call(tier)
                }
            }
        }
        return branches
    }

    // ── Test branches (parallel map) ─────────────────────────────────────────

    Map testBranches() {
        def branches = [:]
        ['backend', 'frontend'].each { tier ->
            if (!tierEnabled(tier)) return
            def tcfg = tierCfg(tier)
            def tool = tcfg.get('buildTool', 'docker-only')
            if (tool == 'docker-only') return

            branches["Test: ${tier}"] = {
                steps.stage("Test ${tier.capitalize()}") {
                    steps.dir(tcfg.get('context', tier)) {
                        def cmd = BUILD_TOOLS[tool]?.test
                        if (cmd) {
                            steps.sh cmd
                        }
                    }
                }
            }
        }
        return branches
    }

    void collectTestReports() {
        ['backend', 'frontend'].each { tier ->
            if (!tierEnabled(tier)) return
            def tcfg = tierCfg(tier)
            def reportsPattern = tcfg.get('testReports', '**/test-results/**/*.xml')
            try {
                steps.junit allowEmptyResults: true, testResults: reportsPattern
            } catch (ignored) {
                steps.echo "No test reports found for ${tier}."
            }
        }
    }

    // ── Docker branches (parallel map) ───────────────────────────────────────

    Map dockerBranches() {
        def branches = [:]
        ['backend', 'frontend'].each { tier ->
            if (!tierEnabled(tier)) return
            def tcfg = tierCfg(tier)

            branches["Docker: ${tier}"] = {
                steps.stage("Docker ${tier.capitalize()}") {
                    docker.buildAndPush(
                        image     : tcfg.image,
                        context   : tcfg.get('context', tier),
                        dockerfile: tcfg.get('dockerfile', "${tcfg.get('context', tier)}/Dockerfile"),
                        buildArgs : tcfg.get('buildArgs', [:]),
                    )
                }
            }
        }
        return branches
    }

    // ── Kubernetes deploy ─────────────────────────────────────────────────────

    void deployTier(String tier) {
        def tcfg = tierCfg(tier)
        steps.echo "Deploying tier: ${tier}"

        try {
            switch (tcfg.get('type', tcfg.get('k8sType', 'manifest'))) {
                case 'helm':
                    k8s.helmDeploy(tier, tcfg)
                    break
                case 'manifest':
                    k8s.manifestApply(tier, tcfg)
                    break
                case 'skip':
                    steps.echo "Tier '${tier}' deploy is set to skip."
                    break
                default:
                    steps.error "Unknown deploy type for tier '${tier}'"
            }

            if (tcfg.get('waitFor', false)) {
                k8s.waitForRollout(tier, tcfg)
            }

            if (tcfg.healthCheck) {
                k8s.healthCheck(tier, tcfg.healthCheck as String)
            }

        } catch (err) {
            if (cfg.onFailure) {
                cfg.onFailure.call(tier, err.getMessage())
            }
            throw err
        }
    }

    // ── Smoke test ────────────────────────────────────────────────────────────

    void smokeTest() {
        def url = cfg.get('smokeTestUrl', '')
        if (!url) {
            steps.echo 'No smokeTestUrl configured — skipping smoke test.'
            return
        }
        steps.retry(3) {
            steps.sh "curl -fsSL --max-time 10 '${url}' -o /dev/null"
        }
        steps.echo "Smoke test passed: ${url}"
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    void notifySuccess() {
        def slack = cfg.get('slackChannel', '')
        if (slack) {
            steps.slackSend(
                channel: slack,
                color  : 'good',
                message: "✅ *${steps.env.JOB_NAME}* #${steps.env.BUILD_NUMBER} deployed successfully. (<${steps.env.BUILD_URL}|Open>)",
            )
        }
    }

    void notifyFailure() {
        def slack = cfg.get('slackChannel', '')
        if (slack) {
            steps.slackSend(
                channel: slack,
                color  : 'danger',
                message: "❌ *${steps.env.JOB_NAME}* #${steps.env.BUILD_NUMBER} failed. (<${steps.env.BUILD_URL}|Open>)",
            )
        }
    }
}
