package com.pipeline.deployer

/**
 * K8sDeployer — wraps kubectl and Helm operations for tier deployments.
 *
 * All kubectl/helm commands run inside `withKubeConfig` so credentials are
 * never written to the workspace or exposed in logs.
 */
class K8sDeployer implements Serializable {

    private final def steps
    private final Map cfg

    K8sDeployer(def steps, Map cfg) {
        this.steps = steps
        this.cfg   = cfg
    }

    // ── Helm deploy ───────────────────────────────────────────────────────────

    void helmDeploy(String tier, Map tcfg) {
        def release   = tcfg.release   ?: "${cfg.get('appName', 'app')}-${tier}"
        def chart     = tcfg.chart     ?: error("helmDeploy: chart is required for tier '${tier}'")
        def namespace = tcfg.namespace ?: cfg.get('namespace', 'default')
        def valueFile = tcfg.get('values', '')
        def imageTag  = cfg.get('imageTag', steps.env.BUILD_NUMBER)
        def timeout   = cfg.get('helmTimeout', '5m')

        withKube {
            def valuesFlag = valueFile ? "--values ${valueFile}" : ''
            def setFlags = [
                "image.tag=${imageTag}",
                "image.repository=${cfg.registry}/${tcfg.image ?: tier}",
            ].collect { "--set ${it}" }.join(' ')

            steps.sh """
                helm upgrade --install ${release} ${chart} \\
                  --namespace ${namespace} \\
                  --create-namespace \\
                  --atomic \\
                  --timeout ${timeout} \\
                  --history-max 5 \\
                  ${valuesFlag} \\
                  ${setFlags}
            """.stripIndent()
        }
    }

    // ── Raw manifest apply ────────────────────────────────────────────────────

    void manifestApply(String tier, Map tcfg) {
        def namespace = tcfg.namespace ?: cfg.get('namespace', 'default')
        def k8sDir    = tcfg.get('k8sDir', "k8s/${tier}/")
        def imageTag  = cfg.get('imageTag', steps.env.BUILD_NUMBER)
        def registry  = cfg.registry
        def image     = tcfg.image ?: tier

        withKube {
            // Substitute IMAGE_TAG and REGISTRY placeholders in manifests using
            // envsubst so the files themselves don't need to be edited.
            steps.sh """
                export IMAGE_TAG="${imageTag}"
                export REGISTRY="${registry}"
                export IMAGE="${registry}/${image}:${imageTag}"
                export NAMESPACE="${namespace}"

                for f in \$(ls ${k8sDir}*.yaml ${k8sDir}*.yml 2>/dev/null); do
                  envsubst < "\$f" | kubectl apply -f - --namespace ${namespace}
                done
            """.stripIndent()
        }
    }

    // ── Rollout health wait ───────────────────────────────────────────────────

    void waitForRollout(String tier, Map tcfg) {
        def namespace  = tcfg.namespace ?: cfg.get('namespace', 'default')
        def deployment = tcfg.get('deploymentName', tier)
        def timeout    = tcfg.get('rolloutTimeout', '300s')

        withKube {
            steps.sh """
                kubectl rollout status deployment/${deployment} \\
                  --namespace ${namespace} \\
                  --timeout=${timeout}
            """.stripIndent()
        }
    }

    // ── HTTP health check ─────────────────────────────────────────────────────

    void healthCheck(String tier, String url) {
        steps.retry(cfg.get('healthCheckRetries', 5) as int) {
            steps.sleep(cfg.get('healthCheckDelaySec', 10) as int)
            steps.sh "curl -fsSL --max-time 15 '${url}' -o /dev/null"
        }
        steps.echo "${tier} health check passed: ${url}"
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void withKube(Closure body) {
        def credId = cfg.get('kubeconfig', 'kubeconfig')
        steps.withKubeConfig([credentialsId: credId]) {
            body()
        }
    }
}
