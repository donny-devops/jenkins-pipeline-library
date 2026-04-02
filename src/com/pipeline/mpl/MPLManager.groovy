package com.pipeline.mpl

/**
 * MPLManager — resolves, loads, and executes pipeline modules.
 *
 * Module resolution priority (first match wins):
 *   1. Project override : <workspace>/.jenkins/modules/<Name>.groovy
 *   2. Library default  : resources/mpl/modules/<Name>.groovy
 *
 * Modules are plain Groovy scripts that receive a `steps` and `config` binding.
 * They are CPS-safe when invoked via evaluate() inside a @NonCPS context; for
 * true sandbox compliance in regulated environments, use the `load()` variant
 * (see loadProjectModule) which requires the module file to be present in the
 * workspace.
 */
class MPLManager implements Serializable {

    private final def steps
    private final Map  cfg

    /** Tracks where each module was loaded from for debugging/audit. */
    private final Map<String, String> moduleSources = [:]

    /** Default stage execution order. */
    static final List<String> DEFAULT_ORDER = [
        'Build', 'Test', 'SecurityScan', 'Docker', 'Deploy'
    ]

    MPLManager(def steps, Map cfg) {
        this.steps = steps
        this.cfg   = cfg
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    void init() {
        steps.echo "MPL initialising — checking for project-level module overrides…"
        resolveOrder().each { name ->
            if (shouldSkip(name)) {
                moduleSources[name] = 'SKIPPED'
                return
            }
            def projectPath = ".jenkins/modules/${name}.groovy"
            if (steps.fileExists(projectPath)) {
                moduleSources[name] = "project:${projectPath}"
            } else {
                moduleSources[name] = "library:mpl/modules/${name}.groovy"
            }
        }
    }

    void runAll() {
        resolveOrder().each { name ->
            if (shouldSkip(name)) {
                steps.echo "MPL: skipping module '${name}' (in skip list)"
                return
            }
            if (!conditionMet(name)) {
                steps.echo "MPL: skipping module '${name}' (condition returned false)"
                return
            }
            steps.stage(name) {
                runModule(name)
            }
        }
    }

    void runPost() {
        def postModule = cfg.get('postModule', '')
        if (postModule) {
            runModule(postModule)
        }
    }

    // ── Module resolution & execution ─────────────────────────────────────────

    /**
     * Execute a named module.
     * Tries project override first, falls back to library default.
     */
    void runModule(String name) {
        def source = moduleSources.get(name, "library:mpl/modules/${name}.groovy")
        steps.echo "MPL: running module '${name}' from ${source}"

        def moduleCfg = buildModuleCfg(name)

        if (source.startsWith('project:')) {
            // Load directly from workspace — no evaluate(), fully CPS-safe.
            def script = steps.load(source.replace('project:', ''))
            if (script.metaClass.respondsTo(script, 'run', Map)) {
                script.run(moduleCfg)
            } else {
                script.call(moduleCfg)
            }
        } else {
            // Library resource — evaluate with binding.
            def scriptText = steps.libraryResource(source.replace('library:', ''))
            runLibraryScript(scriptText, moduleCfg)
        }
    }

    /**
     * Evaluate a library resource script with a controlled binding.
     * NOTE: requires Script Security approval for new Groovy class/method usage
     * in non-permissive Jenkins sandbox configurations.
     */
    @com.cloudbees.groovy.cps.NonCPS
    private void runLibraryScript(String scriptText, Map moduleCfg) {
        def binding = new Binding()
        binding.setVariable('steps',  steps)
        binding.setVariable('config', moduleCfg)
        binding.setVariable('env',    steps.env)

        def shell = new GroovyShell(this.class.classLoader, binding)
        shell.evaluate(scriptText)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    List<String> resolveOrder() {
        return (cfg.get('stageOrder', DEFAULT_ORDER) as List<String>).asImmutable()
    }

    boolean shouldSkip(String name) {
        def skipList = cfg.get('skip', []) as List
        return skipList.contains(name)
    }

    boolean conditionMet(String name) {
        def conditions = cfg.get('conditions', [:]) as Map
        def cond = conditions[name]
        return cond == null || cond.call() == true
    }

    /**
     * Merge global cfg with module-specific cfg.
     * Module config wins on key conflicts.
     */
    private Map buildModuleCfg(String name) {
        def globalCopy  = new HashMap(cfg)
        def modulePatch = (cfg.modules?.get(name) ?: [:]) as Map
        globalCopy.putAll(modulePatch)
        globalCopy.remove('modules')     // prevent recursive nesting
        globalCopy.remove('conditions')
        globalCopy.remove('stageOrder')
        globalCopy.remove('skip')
        globalCopy['_moduleName'] = name
        return globalCopy
    }

    String moduleSourceReport() {
        moduleSources.collect { name, src -> "  ${name.padRight(16)} ← ${src}" }.join('\n')
    }

    void notify(String status) {
        def channel = cfg.get('slackChannel', '')
        if (!channel) return
        def icon  = status == 'SUCCESS' ? '✅' : '❌'
        def color = status == 'SUCCESS' ? 'good' : 'danger'
        steps.slackSend(
            channel: channel,
            color  : color,
            message: "${icon} *MPL* ${steps.env.JOB_NAME} #${steps.env.BUILD_NUMBER} — ${status} (<${steps.env.BUILD_URL}|Open>)",
        )
    }
}
