package com.pipeline.deployer

/**
 * DockerBuilder — wraps Docker image build and push operations.
 *
 * Handles multi-platform builds (when enabled), registry authentication,
 * tag strategy (semver, branch-based, or custom), and image signing stubs.
 */
class DockerBuilder implements Serializable {

    private final def steps
    private final Map cfg

    DockerBuilder(def steps, Map cfg) {
        this.steps = steps
        this.cfg   = cfg
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Build an image and push all computed tags to the registry.
     *
     * @param opts.image      Short image name, e.g. "myapp/backend"
     * @param opts.context    Docker build context directory
     * @param opts.dockerfile Path to Dockerfile
     * @param opts.buildArgs  Map of --build-arg entries
     */
    void buildAndPush(Map opts) {
        def fullImage = "${cfg.registry}/${opts.image}"
        def tags      = computeTags()
        def buildArgsStr = buildArgsFragment(opts.buildArgs ?: [:])
        def primaryTag = "${fullImage}:${tags[0]}"

        steps.withCredentials([
            steps.usernamePassword(
                credentialsId: cfg.get('registryCredential', 'registry-credentials'),
                usernameVariable: 'DOCKER_USER',
                passwordVariable: 'DOCKER_PASS',
            )
        ]) {
            steps.sh "echo \"\$DOCKER_PASS\" | docker login ${cfg.registry} -u \"\$DOCKER_USER\" --password-stdin"
        }

        // Build with the first (primary) tag.
        steps.sh """
            docker build \\
              --pull \\
              --file ${opts.dockerfile} \\
              ${buildArgsStr} \\
              --label "org.opencontainers.image.source=\${GIT_URL:-unknown}" \\
              --label "org.opencontainers.image.revision=\${GIT_COMMIT:-unknown}" \\
              --label "org.opencontainers.image.created=\$(date -u +%Y-%m-%dT%H:%M:%SZ)" \\
              --tag ${primaryTag} \\
              ${opts.context}
        """.stripIndent()

        // Apply additional tags locally (cheap — just metadata).
        tags.drop(1).each { tag ->
            steps.sh "docker tag ${primaryTag} ${fullImage}:${tag}"
        }

        // Push all tags.
        tags.each { tag ->
            steps.sh "docker push ${fullImage}:${tag}"
        }

        steps.echo "Pushed ${tags.size()} tag(s) for ${fullImage}: ${tags.join(', ')}"
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the ordered list of tags to apply.
     * Priority: explicit imageTag → BUILD_NUMBER; always appends branch slug.
     */
    List<String> computeTags() {
        def primary  = cfg.get('imageTag', steps.env.BUILD_NUMBER ?: 'latest')
        def branch   = slugify(steps.env.BRANCH_NAME ?: 'unknown')
        def gitShort = steps.env.GIT_COMMIT ? steps.env.GIT_COMMIT.take(8) : null

        def tags = [primary.toString(), branch]
        if (gitShort) tags << gitShort
        if (branch == 'main' || branch == 'master') tags << 'latest'
        return tags.unique()
    }

    private String slugify(String s) {
        return s.replaceAll(/[^a-zA-Z0-9._-]/, '-').toLowerCase().replaceAll(/-+/, '-').take(128)
    }

    private String buildArgsFragment(Map args) {
        return args.collect { k, v -> "--build-arg ${k}=${v}" }.join(' ')
    }
}
