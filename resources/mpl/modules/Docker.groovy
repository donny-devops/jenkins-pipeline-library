/**
 * MPL Default Module: Docker
 *
 * Builds and pushes a Docker image. Delegates to DockerBuilder for tag
 * computation and registry authentication.
 *
 * Config keys:
 *   registry           - image registry host (required)
 *   image              - short image name, e.g. "myteam/api" (required)
 *   dockerfile         - path to Dockerfile (default: "Dockerfile")
 *   context            - Docker build context (default: ".")
 *   buildArgs          - Map of --build-arg entries
 *   registryCredential - Jenkins credential ID for registry login (default: "registry-credentials")
 *   imageTag           - primary tag; defaults to BUILD_NUMBER
 *   scanAfterPush      - run Trivy image scan post-push (default: true)
 *   trivySeverity      - severities to fail on (default: "CRITICAL")
 */

def registry   = config.registry ?: steps.error('Docker module: registry is required')
def image      = config.image    ?: steps.error('Docker module: image is required')
def dockerfile = config.get('dockerfile', 'Dockerfile')
def context    = config.get('context', '.')
def credId     = config.get('registryCredential', 'registry-credentials')
def imageTag   = config.get('imageTag', env.BUILD_NUMBER ?: 'latest')
def scanAfter  = config.get('scanAfterPush', true)

def fullImage  = "${registry}/${image}"
def branch     = (env.BRANCH_NAME ?: 'unknown').replaceAll(/[^a-zA-Z0-9._-]/, '-').toLowerCase()
def tags       = [imageTag, branch]
if (env.GIT_COMMIT) tags << env.GIT_COMMIT.take(8)
if (branch in ['main', 'master']) tags << 'latest'
tags = tags.unique()

def primaryTag = "${fullImage}:${tags[0]}"

// Build
steps.sh """
    docker build \\
      --pull \\
      --file ${dockerfile} \\
      --tag ${primaryTag} \\
      --label "org.opencontainers.image.revision=\${GIT_COMMIT:-unknown}" \\
      --label "org.opencontainers.image.created=\$(date -u +%Y-%m-%dT%H:%M:%SZ)" \\
      ${(config.buildArgs as Map ?: [:]).collect { k, v -> "--build-arg ${k}=${v}" }.join(' ')} \\
      ${context}
""".stripIndent()

// Tag all variants
tags.drop(1).each { tag ->
    steps.sh "docker tag ${primaryTag} ${fullImage}:${tag}"
}

// Push
steps.withCredentials([
    steps.usernamePassword(
        credentialsId   : credId,
        usernameVariable: 'DOCKER_USER',
        passwordVariable: 'DOCKER_PASS',
    )
]) {
    steps.sh "echo \"\$DOCKER_PASS\" | docker login ${registry} -u \"\$DOCKER_USER\" --password-stdin"
    tags.each { tag ->
        steps.sh "docker push ${fullImage}:${tag}"
    }
}

steps.echo "Docker module: pushed tags ${tags} for ${fullImage}"

// Post-push Trivy image scan
if (scanAfter) {
    def severity = config.get('trivySeverity', 'CRITICAL')
    def trivyExit = steps.sh(
        script: """
            trivy image \\
              --severity ${severity} \\
              --exit-code 0 \\
              --format sarif \\
              --output trivy-image-results.sarif \\
              ${primaryTag}
        """.stripIndent(),
        returnStatus: true,
    )
    steps.archiveArtifacts artifacts: 'trivy-image-results.sarif', allowEmptyArchive: true
    if (trivyExit != 0 && config.get('failOnVulnerability', false)) {
        steps.error "Docker module: Trivy found ${severity} vulnerabilities in ${primaryTag}"
    }
}
