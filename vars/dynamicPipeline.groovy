def call(Map opts = [:]) {
    def agentLabel = (opts.get('agent', 'any') ?: 'any').toString()
    if (!(agentLabel ==~ /^[A-Za-z0-9_.-]{1,64}$/)) {
        error "dynamicPipeline: agent label must be 1-64 characters of letters, numbers, dot, underscore, or dash"
    }

    def configLoader = new com.pipeline.dynamic.PipelineConfig(this, opts)
    def factory      = new com.pipeline.dynamic.StageFactory(this, opts)

    // Pipeline configuration is read from SCM, so we need a checkout first.
    node(agentLabel) {
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
        globalEnv.each { key, value ->
            def name = key?.toString() ?: ""
            def text = value?.toString() ?: ""
            if (!(name ==~ /^[A-Za-z_][A-Za-z0-9_]{0,63}$/)) {
                error "dynamicPipeline: illegal environment key '${name}'"
            }
            if (text.contains("\n") || text.contains("\r")) {
                error "dynamicPipeline: environment value for ${name} must be a single line"
            }
        }
        withEnv(globalEnv.collect { k, v -> "${k}=${v}" }) {

            // Walk stage definitions and execute them.
            factory.execute(stageDefs, pipelineCfg, runtimeSkip)

        }
    }
}
