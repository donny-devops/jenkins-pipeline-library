/**
 * MPL Default Module: SecurityScan
 *
 * Runs SAST (Semgrep), dependency vulnerability scanning (Trivy / OWASP DC),
 * and secrets detection (Gitleaks). Results are uploaded as SARIF reports.
 *
 * Config keys:
 *   skipSast          - skip Semgrep SAST (default: false)
 *   skipDependencies  - skip Trivy dependency scan (default: false)
 *   skipSecrets       - skip Gitleaks (default: false)
 *   failOnCritical    - fail the build on CRITICAL findings (default: false)
 *   trivySeverity     - comma-separated severities to report (default: "HIGH,CRITICAL")
 *   semgrepConfig     - Semgrep ruleset slug or path (default: "p/owasp-top-ten")
 */

def failOnCritical   = config.get('failOnCritical', false)
def skipSast         = config.get('skipSast', false)
def skipDeps         = config.get('skipDependencies', false)
def skipSecrets      = config.get('skipSecrets', false)
def trivySeverity    = config.get('trivySeverity', 'HIGH,CRITICAL')
def semgrepConfig    = config.get('semgrepConfig', 'p/owasp-top-ten')

// ── SAST (Semgrep) ────────────────────────────────────────────────────────────
if (!skipSast) {
    steps.echo "SecurityScan: Running Semgrep SAST…"
    def exitCode = steps.sh(
        script: """
            semgrep scan \\
              --config ${semgrepConfig} \\
              --sarif \\
              --output semgrep-results.sarif \\
              --quiet \\
              .
        """.stripIndent(),
        returnStatus: true,
    )

    if (steps.fileExists('semgrep-results.sarif')) {
        try {
            steps.recordIssues(
                tool: steps.sarif(pattern: 'semgrep-results.sarif', id: 'semgrep', name: 'Semgrep SAST'),
                qualityGates: failOnCritical ? [[threshold: 1, type: 'TOTAL_HIGH', unstable: false]] : [],
            )
        } catch (ignored) {
            steps.archiveArtifacts artifacts: 'semgrep-results.sarif', allowEmptyArchive: true
        }
    }

    if (failOnCritical && exitCode != 0) {
        steps.error "SecurityScan: Semgrep found policy violations (exit ${exitCode})"
    }
}

// ── Dependency vulnerabilities (Trivy) ───────────────────────────────────────
if (!skipDeps) {
    steps.echo "SecurityScan: Running Trivy filesystem scan…"
    def trivyExit = steps.sh(
        script: """
            trivy fs \\
              --severity ${trivySeverity} \\
              --format sarif \\
              --output trivy-fs-results.sarif \\
              --exit-code ${failOnCritical ? 1 : 0} \\
              .
        """.stripIndent(),
        returnStatus: true,
    )

    if (steps.fileExists('trivy-fs-results.sarif')) {
        steps.archiveArtifacts artifacts: 'trivy-fs-results.sarif', allowEmptyArchive: true
    }

    if (failOnCritical && trivyExit != 0) {
        steps.error "SecurityScan: Trivy found ${trivySeverity} vulnerabilities in dependencies."
    }
}

// ── Secret detection (Gitleaks) ───────────────────────────────────────────────
if (!skipSecrets) {
    steps.echo "SecurityScan: Running Gitleaks…"
    def leaksExit = steps.sh(
        script: """
            gitleaks detect \\
              --source . \\
              --report-format sarif \\
              --report-path gitleaks-results.sarif \\
              --exit-code 1 \\
              --no-git
        """.stripIndent(),
        returnStatus: true,
    )

    if (steps.fileExists('gitleaks-results.sarif')) {
        steps.archiveArtifacts artifacts: 'gitleaks-results.sarif', allowEmptyArchive: true
    }

    if (leaksExit != 0) {
        // Secrets always fail regardless of failOnCritical.
        steps.error 'SecurityScan: Gitleaks detected potential secrets in the repository!'
    }
}

steps.echo "SecurityScan module complete."
