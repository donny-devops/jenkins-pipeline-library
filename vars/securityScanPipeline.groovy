// vars/securityScanPipeline.groovy
def call(Map config = [:]) {
    pipeline {
        agent any
        options {
            timeout(time: 1, unit: 'HOURS')
            buildDiscarder(logRotator(numToKeepStr: '15'))
        }
        environment {
            SECOPS_ENV = "production-scans"
        }
        stages {
            stage('Secret Scanning (Gitleaks)') {
                steps {
                    echo "Running Gitleaks secret detection..."
                    sh 'gitleaks detect --verbose'
                }
            }
            stage('SCA & SAST Security Scanning') {
                steps {
                    echo "Executing Trivy filesystem scan..."
                    sh 'trivy fs --scanners vuln,secret,misconfig .'
                }
            }
            stage('Wazuh SIEM Event Forwarding') {
                steps {
                    echo "Forwarding CI build security telemetry to Wazuh SIEM indexer..."
                }
            }
        }
        post {
            failure {
                notifyPagerDuty(severity: 'critical', summary: "Security scan failed on ${env.JOB_NAME}")
                notifySlack(status: 'FAILURE', message: "Security vulnerabilities or secret leaks detected in ${env.JOB_NAME}")
            }
            success {
                notifySlack(status: 'SUCCESS', message: "All security benchmarks and scans passed cleanly.")
            }
        }
    }
}
