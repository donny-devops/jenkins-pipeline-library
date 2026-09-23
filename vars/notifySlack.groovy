// vars/notifySlack.groovy
def call(Map config = [:]) {
    def channel = config.channel ?: "#devops-alerts"
    def status = config.status ?: "SUCCESS"
    def message = config.message ?: "Pipeline ${env.JOB_NAME} #${env.BUILD_NUMBER} finished with status: ${status}"
    def color = (status == "SUCCESS") ? "#36a64f" : "#dc3545"

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
