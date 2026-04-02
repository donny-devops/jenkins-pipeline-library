/**
 * dynamicPipeline — Dynamic Stage Generation Library
 *
 * Reads a YAML or JSON pipeline config from the application repository and
 * generates Jenkins stages at runtime. Every aspect of the pipeline — which
 * stages run, their parallelism, conditions, timeouts, and step scripts — is
 * driven by the config file rather than the Jenkinsfile.
 *
 * ── Config file location (searched in order) ────────────────────────────────
 *   1. pipeline.yaml   (project root)
 *   2. pipeline.yml
 *   3. pipeline.json
 *   4. .jenkins/pipeline.yaml
 *   5. Value of configPath parameter
 *
 * ── Jenkinsfile usage (minimal) ─────────────────────────────────────────────
 *
 *   @Library('pipeline-library') _
 *   dynamicPipeline()
 *
 * ── Jenkinsfile usage (with overrides) ──────────────────────────────────────
 *
 *   @Library('pipeline-library') _
 *
 *   dynamicPipeline(
 *     configPath   : '.jenkins/pipeline.yaml',
 *     globalEnv    : [DEPLOY_ENV: 'staging'],
 *     skipStages   : ['performance-tests'],   // runtime override
 *     onStageError : { name, err -> pagerdutyAlert(err.getMessage()) },
 *   )
 *
 * ── Minimal pipeline.yaml example ───────────────────────────────────────────
 *
 *   pipeline:
 *     agent: docker
 *     timeout: 60
 *
 *   stages:
 *     - name: Build
 *       script: mvn clean package -DskipTests -B
 *
 *     - name: Unit Tests
 *       script: mvn test -B
 *       reports:
 *         junit: "**/surefire-reports/*.xml"
 *
 *     - name: Integration Tests
 *       enabled: "{{ env.BRANCH_NAME != 'feature/*' }}"
 *       script: mvn verify -Pintegration-tests -B
 *
 *     - name: Performance Tests
 *       enabled: false
 *       parallel: false
 *
 *     - name: Docker Build & Push
 *       type: docker
 *       image: myteam/api
 *       registry: registry.example.com
 *
 *     - name: Deploy Staging
 *       type: deploy
 *       condition: branch in ['main', 'release/*']
 *       dependsOn: [Docker Build & Push]
 *
 * See resources/pipeline-configs/example-pipeline.yaml for the full schema.
 */

def call(Map opts = [:]) {
    def configLoader = new com.pipeline.dynamic.PipelineConfig(this, opts)
    def factory      = new com.pipeline.dynamic.StageFactory(this, opts)

    // Pipeline configuration is read from SCM, so we need a checkout first.
    node(opts.get('agent', 'any') as String) {
        stage('Checkout & Load Config') {
            checkout scm
            configLoader.load()
        }

        def pipelineCfg = configLoader.pipeline
        def stageDefs   = configLoader.stages

        // Apply runtime skip overrides from call opts.
        def runtimeSkip = (opts.skipStages ?: []) as List<String>

        // Inject global environment variables.
        def globalEnv = (opts.globalEnv ?: (pipelineCfg.get('env', [:]) ?: [:])) as Map
        withEnv(globalEnv.collect { k, v -> "${k}=${v}" }) {

            // Walk stage definitions and execute them.
            factory.execute(stageDefs, pipelineCfg, runtimeSkip)

        }
    }
}
