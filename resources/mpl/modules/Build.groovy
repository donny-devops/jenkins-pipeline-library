/**
 * MPL Default Module: Build
 *
 * Compiles/packages the application. Supports maven, gradle, npm, yarn, python.
 * Override by placing .jenkins/modules/Build.groovy in your project repo.
 *
 * Config keys (all optional):
 *   tool        - build tool: maven | gradle | npm | yarn | python (default: maven)
 *   goals       - tool-specific goals/targets (overrides default)
 *   buildDir    - working directory for build commands (default: ".")
 *   jdkVersion  - JDK tool name configured in Jenkins (maven/gradle only)
 *   nodeVersion - Node tool name configured in Jenkins (npm/yarn only)
 */

def tool     = config.get('tool', 'maven')
def buildDir = config.get('buildDir', '.')

steps.dir(buildDir) {
    switch (tool) {
        case 'maven':
            def goals = config.get('goals', 'clean package -DskipTests -B -q')
            if (config.jdkVersion) {
                steps.tool name: config.jdkVersion, type: 'jdk'
            }
            steps.sh "mvn ${goals}"
            steps.archiveArtifacts artifacts: '**/target/*.jar,**/target/*.war', allowEmptyArchive: true
            break

        case 'gradle':
            def goals = config.get('goals', 'clean assemble')
            steps.sh "./gradlew ${goals}"
            steps.archiveArtifacts artifacts: '**/build/libs/*.jar', allowEmptyArchive: true
            break

        case 'npm':
            def script = config.get('goals', 'ci && npm run build')
            steps.sh "npm ${script}"
            break

        case 'yarn':
            steps.sh 'yarn install --frozen-lockfile'
            steps.sh "yarn ${config.get('goals', 'build')}"
            break

        case 'python':
            steps.sh 'pip install -r requirements.txt'
            if (config.goals) {
                steps.sh config.goals as String
            }
            break

        default:
            steps.error "Build module: unknown tool '${tool}'"
    }
}

steps.echo "Build module complete (tool=${tool})"
