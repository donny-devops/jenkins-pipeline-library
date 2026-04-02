/**
 * MPL Default Module: Test
 *
 * Runs unit/integration tests, publishes JUnit results, and optionally
 * enforces a minimum code coverage threshold.
 *
 * Config keys:
 *   tool          - maven | gradle | npm | yarn | python (default: maven)
 *   testGoals     - override test command
 *   testReports   - JUnit XML glob (default: **/test-results/**/*.xml)
 *   coverageMin   - minimum line coverage % (0 = skip check, default: 0)
 *   failFast      - abort pipeline on first test failure (default: false)
 */

def tool      = config.get('tool', 'maven')
def failFast  = config.get('failFast', false)
def reportsGlob = config.get('testReports', '**/test-results/**/*.xml,**/surefire-reports/**/*.xml')

try {
    switch (tool) {
        case 'maven':
            def goals = config.get('testGoals', 'test jacoco:report')
            steps.sh "mvn ${goals} -B"
            break

        case 'gradle':
            steps.sh "./gradlew ${config.get('testGoals', 'test jacocoTestReport')}"
            break

        case 'npm':
            steps.sh "npm test -- --watchAll=false --ci"
            break

        case 'yarn':
            steps.sh 'yarn test --watchAll=false --ci'
            break

        case 'python':
            steps.sh "pytest ${config.get('testGoals', '--junitxml=test-results/results.xml --cov=. --cov-report=xml')}"
            break

        default:
            steps.error "Test module: unknown tool '${tool}'"
    }
} catch (err) {
    if (failFast) throw err
    steps.currentBuild.result = 'UNSTABLE'
    steps.echo "Tests failed but failFast=false — marking build UNSTABLE. Error: ${err.getMessage()}"
} finally {
    steps.junit allowEmptyResults: true, testResults: reportsGlob

    // Coverage gate
    def minCoverage = config.get('coverageMin', 0) as int
    if (minCoverage > 0) {
        try {
            steps.jacoco(
                execPattern      : '**/jacoco.exec',
                classPattern     : '**/classes',
                sourcePattern    : '**/src/main/java',
                minimumLineCoverage: minCoverage.toString(),
            )
        } catch (ignored) {
            steps.echo "Jacoco publisher not available — skipping coverage gate."
        }
    }
}

steps.echo "Test module complete (tool=${tool})"
