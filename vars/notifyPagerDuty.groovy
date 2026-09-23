// vars/notifyPagerDuty.groovy
def call(Map config = [:]) {
    def routingKey = config.routingKey ?: env.PAGERDUTY_ROUTING_KEY
    def summary = config.summary ?: "[Jenkins Pipeline] ${env.JOB_NAME} #${env.BUILD_NUMBER} Failure Alert"
    def severity = config.severity ?: "error"
    def source = config.source ?: "jenkins-ci-pipeline"
    def dedupKey = config.dedupKey ?: "jenkins-${env.JOB_NAME}-${env.BUILD_NUMBER}"

    echo "[PagerDuty] Dispatching incident alert: ${summary} (${severity})"

    def payload = [
        routing_key: routingKey,
        event_action: "trigger",
        dedup_key: dedupKey,
        payload: [
            summary: summary,
            severity: severity,
            source: source,
            component: env.JOB_NAME,
            custom_details: [
                build_url: env.BUILD_URL,
                git_branch: env.GIT_BRANCH ?: "main",
                triggered_by: env.BUILD_USER ?: "system"
            ]
        ]
    ]

    return payload
}
