package com.pipeline.dynamic

/**
 * StageFactory — turns parsed stage definitions into live Jenkins stage() blocks.
 *
 * Supported stage types:
 *   shell   (default) — runs sh/bat script
 *   docker             — builds and pushes a Docker image
 *   deploy             — kubectl/helm deploy
 *   test               — runs tests and publishes reports
 *   parallel           — fan-out: child stages listed under "stages:" run in parallel
 *
 * Every stage honours these common fields:
 *   name        - display name (required)
 *   enabled     - boolean or Groovy expression string (default: true)
 *   condition   - alias for enabled
 *   dependsOn   - list of stage names that must have succeeded (checked at runtime)
 *   timeout     - stage timeout in minutes (default: 30)
 *   retries     - number of retry attempts on failure (default: 0)
 *   agent       - label expression to run this specific stage on (optional)
 *   environment - map of env vars scoped to this stage
 *   onSuccess   - shell command to run after success
 *   onFailure   - shell command to run after failure
 */
class StageFactory implements Serializable {

    private final def steps
    private final Map opts

    /** Tracks which stages completed successfully for dependsOn enforcement. */
    private final Set<String> completedStages = [] as Set

    StageFactory(def steps, Map opts) {
        this.steps = steps
        this.opts  = opts
    }

    // ── Main entry point ──────────────────────────────────────────────────────

    void execute(List<Map> stageDefs, Map pipelineCfg, List<String> runtimeSkip) {
        stageDefs.each { stageDef ->
            runStageDef(stageDef, pipelineCfg, runtimeSkip)
        }
    }

    // ── Stage dispatch ────────────────────────────────────────────────────────

    private void runStageDef(Map s, Map pipelineCfg, List<String> runtimeSkip) {
        def name = s.name as String

        // ── Pre-flight checks ──────────────────────────────────────────────
        if (!isEnabled(s, runtimeSkip)) {
            steps.echo "dynamicPipeline: stage '${name}' is disabled — skipping."
            return
        }

        if (!dependenciesMet(s)) {
            steps.echo "dynamicPipeline: stage '${name}' skipped — unmet dependsOn: ${s.dependsOn}"
            return
        }

        // ── Wrap in stage() ────────────────────────────────────────────────
        def agentLabel = s.agent ?: pipelineCfg.get('agent', '')
        def envVars    = ((s.environment ?: [:]) as Map).collect { k, v -> "${k}=${v}" }
        def timeoutMin = (s.get('timeout', 30) as int)
        def retries    = (s.get('retries', 0) as int)
        def stageType  = (s.get('type', 'shell') as String).toLowerCase()

        steps.stage(name) {
            steps.timeout(time: timeoutMin, unit: 'MINUTES') {
                steps.withEnv(envVars) {
                    if (agentLabel) {
                        steps.node(agentLabel) {
                            runWithRetry(retries) {
                                dispatchType(stageType, s, pipelineCfg)
                            }
                        }
                    } else {
                        runWithRetry(retries) {
                            dispatchType(stageType, s, pipelineCfg)
                        }
                    }
                }
            }
        }

        completedStages.add(name)
    }

    private void dispatchType(String type, Map s, Map pipelineCfg) {
        switch (type) {
            case 'shell':
            case null:
                runShellStage(s)
                break
            case 'test':
                runTestStage(s)
                break
            case 'docker':
                runDockerStage(s, pipelineCfg)
                break
            case 'deploy':
                runDeployStage(s, pipelineCfg)
                break
            case 'parallel':
                runParallelStage(s, pipelineCfg)
                break
            default:
                steps.echo "dynamicPipeline: unknown stage type '${type}' — treating as shell."
                runShellStage(s)
        }
    }

    // ── Type implementations ──────────────────────────────────────────────────

    private void runShellStage(Map s) {
        def script = s.script as String
        if (!script) {
            steps.echo "dynamicPipeline: stage '${s.name}' has no script — nothing to do."
            return
        }

        try {
            steps.sh script
            if (s.onSuccess) steps.sh(s.onSuccess as String)
        } catch (err) {
            if (s.onFailure) steps.sh(s.onFailure as String)
            if (opts.onStageError) opts.onStageError.call(s.name, err)
            throw err
        }
    }

    private void runTestStage(Map s) {
        try {
            if (s.script) steps.sh(s.script as String)
        } finally {
            def reports = s.reports as Map ?: [:]
            if (reports.junit) {
                steps.junit allowEmptyResults: true, testResults: reports.junit as String
            }
            if (reports.cobertura) {
                try {
                    steps.cobertura coberturaReportFile: reports.cobertura as String
                } catch (ignored) {
                    steps.archiveArtifacts artifacts: reports.cobertura as String, allowEmptyArchive: true
                }
            }
            if (reports.htmlDir) {
                steps.publishHTML([
                    reportDir  : reports.htmlDir as String,
                    reportFiles: reports.get('htmlIndex', 'index.html') as String,
                    reportName : "${s.name} Report",
                ])
            }
        }
    }

    private void runDockerStage(Map s, Map pipelineCfg) {
        def registry   = s.get('registry', pipelineCfg.get('registry', '')) as String
        def image      = s.image as String ?: steps.error("dynamicPipeline: docker stage '${s.name}' requires 'image'")
        def dockerfile = s.get('dockerfile', 'Dockerfile') as String
        def context    = s.get('context', '.') as String
        def tag        = s.get('imageTag', steps.env.BUILD_NUMBER ?: 'latest') as String
        def credId     = s.get('registryCredential', 'registry-credentials') as String
        def fullImage  = registry ? "${registry}/${image}:${tag}" : "${image}:${tag}"

        steps.sh "docker build --pull -f ${dockerfile} -t ${fullImage} ${context}"

        if (registry) {
            steps.withCredentials([
                steps.usernamePassword(credentialsId: credId, usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')
            ]) {
                steps.sh "echo \"\$DOCKER_PASS\" | docker login ${registry} -u \"\$DOCKER_USER\" --password-stdin"
                steps.sh "docker push ${fullImage}"
            }
        }
    }

    private void runDeployStage(Map s, Map pipelineCfg) {
        def credId    = s.get('kubeconfig', pipelineCfg.get('kubeconfig', 'kubeconfig')) as String
        def namespace = s.get('namespace',  pipelineCfg.get('namespace', 'default'))    as String
        def imageTag  = s.get('imageTag',   steps.env.BUILD_NUMBER ?: 'latest')         as String

        steps.withKubeConfig([credentialsId: credId]) {
            if (s.chart) {
                def release     = s.get('release', s.name.toLowerCase().replaceAll(/\s+/, '-')) as String
                def valuesFlag  = s.values ? "--values ${s.values}" : ''
                def helmTimeout = s.get('helmTimeout', '5m') as String
                steps.sh """
                    helm upgrade --install ${release} ${s.chart} \\
                      --namespace ${namespace} --create-namespace \\
                      --atomic --timeout ${helmTimeout} \\
                      ${valuesFlag} \\
                      --set image.tag=${imageTag}
                """.stripIndent()
            } else if (s.k8sDir || s.manifest) {
                def dir = (s.k8sDir ?: s.manifest) as String
                steps.sh """
                    export IMAGE_TAG="${imageTag}" NAMESPACE="${namespace}"
                    for f in \$(ls ${dir}*.yaml ${dir}*.yml 2>/dev/null); do
                      envsubst < "\$f" | kubectl apply -n ${namespace} -f -
                    done
                """.stripIndent()
            } else {
                steps.error "dynamicPipeline: deploy stage '${s.name}' needs either 'chart' or 'k8sDir'."
            }
        }
    }

    private void runParallelStage(Map s, Map pipelineCfg) {
        def childDefs = (s.stages ?: []) as List<Map>
        if (!childDefs) {
            steps.echo "dynamicPipeline: parallel stage '${s.name}' has no child stages."
            return
        }

        def branches = [:]
        childDefs.each { child ->
            def childName = child.name as String
            branches[childName] = {
                runStageDef(child, pipelineCfg, [])
            }
        }
        steps.parallel branches
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isEnabled(Map s, List<String> runtimeSkip) {
        def name = s.name as String
        if (runtimeSkip.contains(name)) return false

        def raw = s.get('enabled', s.get('condition', true))
        if (raw instanceof Boolean) return raw
        if (raw instanceof String) {
            // Simple branch expression: "branch in ['main', 'release']"
            def branchName = steps.env.BRANCH_NAME ?: ''
            if (raw.startsWith('branch')) {
                def match = raw =~ /branch\s+in\s+\[([^\]]+)\]/
                if (match) {
                    def allowed = match[0][1].split(',').collect { it.trim().replaceAll(/['"]/, '') }
                    return allowed.any { pat ->
                        pat.contains('*') ? branchName ==~ pat.replace('*', '.*') : branchName == pat
                    }
                }
            }
            // Evaluate simple Groovy expression strings.
            try {
                return Eval.me('env', steps.env, raw.toString()) as boolean
            } catch (ignored) {
                steps.echo "WARNING: could not evaluate condition '${raw}' — defaulting to true."
                return true
            }
        }
        return true
    }

    private boolean dependenciesMet(Map s) {
        def deps = (s.dependsOn ?: []) as List<String>
        return deps.every { completedStages.contains(it) }
    }

    private void runWithRetry(int retries, Closure body) {
        if (retries > 0) {
            steps.retry(retries) { body() }
        } else {
            body()
        }
    }
}
