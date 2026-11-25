// vars/notifySlack.groovy
def call(Map config = [:]) {
    def allowed = ["SUCCESS", "FAILURE", "UNSTABLE", "ABORTED"]
    def channel = (config.channel ?: "#devops-alerts").toString().trim()
    def status = (config.status ?: "SUCCESS").toString().trim()
    if (!channel) {
        error "notifySlack: channel is required"
    }
    if (!allowed.contains(status)) {
        error "notifySlack: status must be one of ${allowed}"
    }

    def message = config.message ?: "Pipeline ${env.JOB_NAME} #${env.BUILD_NUMBER} finished with status: ${status}"
    def color = "#dc3545"
    if (status == "SUCCESS") {
        color = "#36a64f"
    } else if (status == "UNSTABLE") {
        color = "#f2c744"
    }

    echo "[Slack] Sending webhook notification to channel ${channel}: ${message}"

    return [
        channel: channel,
        attachments: [
            [
                color: color,
                title: "${env.JOB_NAME} Build #${env.BUILD_NUMBER}",
                title_link: env.BUILD_URL,
                text: message,
                footer: "Jenkins Shared Library",
                ts: System.currentTimeMillis() / 1000
            ]
        ]
    ]
}
