---
name: jenkins-pipeline-library
description: Expert AI agent skill for Jenkins shared libraries, dynamic multi-stage deployment pipelines, SecOps scanners, PagerDuty alerting, and Slack webhooks.
---

# Jenkins Pipeline Library Skill

## Available Global Variables
- `securityScanPipeline.groovy`: Complete pipeline orchestrating Gitleaks, Trivy, and SIEM forwarding.
- `notifyPagerDuty.groovy`: Generates structured events for PagerDuty Events API v2.
- `notifySlack.groovy`: Formats rich Slack notifications with color-coded build states.
