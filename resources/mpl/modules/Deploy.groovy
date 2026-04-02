/**
 * MPL Default Module: Deploy
 *
 * Deploys to Kubernetes via Helm (default) or raw manifests.
 * Delegates to K8sDeployer; project overrides can swap to any deploy target.
 *
 * Config keys:
 *   type         - helm | manifest (default: helm)
 *   kubeconfig   - Jenkins credential ID for kubeconfig (default: "kubeconfig")
 *   namespace    - K8s namespace (default: "default")
 *   registry     - image registry (required for image.repository set flag)
 *   image        - short image name (required)
 *   imageTag     - image tag (default: BUILD_NUMBER)
 *   --- Helm-specific ---
 *   chart        - Helm chart reference (required for type=helm)
 *   release      - Helm release name (required for type=helm)
 *   values       - path to values file (optional)
 *   helmTimeout  - Helm atomic timeout (default: "5m")
 *   --- Manifest-specific ---
 *   k8sDir       - directory containing *.yaml manifests (default: "k8s/")
 */

def deployType = config.get('type', 'helm')
def namespace  = config.get('namespace', 'default')
def credId     = config.get('kubeconfig', 'kubeconfig')
def imageTag   = config.get('imageTag', env.BUILD_NUMBER ?: 'latest')
def registry   = config.get('registry', '')
def image      = config.get('image', '')

steps.withKubeConfig([credentialsId: credId]) {

    switch (deployType) {
        case 'helm':
            def chart       = config.chart   ?: steps.error('Deploy module: chart is required for type=helm')
            def release     = config.release ?: steps.error('Deploy module: release is required for type=helm')
            def valuesFlag  = config.values  ? "--values ${config.values}" : ''
            def helmTimeout = config.get('helmTimeout', '5m')

            steps.sh """
                helm upgrade --install ${release} ${chart} \\
                  --namespace ${namespace} \\
                  --create-namespace \\
                  --atomic \\
                  --timeout ${helmTimeout} \\
                  --history-max 5 \\
                  ${valuesFlag} \\
                  ${registry && image ? "--set image.repository=${registry}/${image}" : ''} \\
                  ${imageTag ? "--set image.tag=${imageTag}" : ''}
            """.stripIndent()
            break

        case 'manifest':
            def k8sDir = config.get('k8sDir', 'k8s/')
            steps.sh """
                export IMAGE_TAG="${imageTag}"
                export REGISTRY="${registry}"
                export IMAGE="${registry}/${image}:${imageTag}"
                export NAMESPACE="${namespace}"

                for f in \$(ls ${k8sDir}*.yaml ${k8sDir}*.yml 2>/dev/null); do
                  envsubst < "\$f" | kubectl apply --namespace ${namespace} -f -
                done
                kubectl rollout status deployment --namespace ${namespace} --timeout=300s
            """.stripIndent()
            break

        default:
            steps.error "Deploy module: unknown type '${deployType}'"
    }
}

steps.echo "Deploy module complete (type=${deployType}, namespace=${namespace})"
