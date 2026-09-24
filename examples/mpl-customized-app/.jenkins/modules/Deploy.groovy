// .jenkins/modules/Deploy.groovy — Project-level override for MPL Deploy stage
def call(Map config) {
    echo "Executing Canary Deployment override for Payment Service..."

    def chart = config.get('chart', 'charts/service')
    def release = config.get('release', 'service')
    def canaryWeight = config.get('canaryWeight', 10)
    def namespace = config.get('namespace', 'staging')

    echo "Deploying canary release ${release}-canary with weight ${canaryWeight}% to ${namespace}"
    sh """
        helm upgrade --install ${release}-canary ${chart} \\
          --namespace ${namespace} \\
          --set canary.enabled=true \\
          --set canary.weight=${canaryWeight}
    """
    echo "Canary deployment completed."
}

return this
