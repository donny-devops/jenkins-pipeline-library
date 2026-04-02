package com.pipeline.dynamic

/**
 * PipelineConfig — discovers and parses the pipeline config file from the workspace.
 *
 * Supports YAML and JSON. Merges library defaults with project-declared settings.
 * Config is validated against a minimal schema before the pipeline runs so that
 * misconfigured files produce clear errors rather than cryptic stage failures.
 */
class PipelineConfig implements Serializable {

    private final def  steps
    private final Map  opts

    /** Parsed top-level pipeline settings. */
    Map pipeline = [:]

    /** Ordered list of stage definition maps. */
    List<Map> stages = []

    /** Search order for config files. */
    static final List<String> SEARCH_PATHS = [
        'pipeline.yaml',
        'pipeline.yml',
        'pipeline.json',
        '.jenkins/pipeline.yaml',
        '.jenkins/pipeline.yml',
        '.jenkins/pipeline.json',
    ]

    PipelineConfig(def steps, Map opts) {
        this.steps = steps
        this.opts  = opts
    }

    // ── Public API ────────────────────────────────────────────────────────────

    void load() {
        def path = resolveConfigPath()
        def raw  = parse(path)

        validate(raw, path)

        pipeline = (raw.pipeline ?: [:]) as Map
        stages   = (raw.stages   ?: [])  as List<Map>

        steps.echo "dynamicPipeline: loaded ${stages.size()} stage(s) from ${path}"
        if (stages) {
            steps.echo "  Stages: ${stages.collect { it.name }.join(' → ')}"
        }
    }

    // ── Resolution ────────────────────────────────────────────────────────────

    private String resolveConfigPath() {
        // Explicit override from call opts takes priority.
        if (opts.configPath) {
            def p = opts.configPath as String
            if (!steps.fileExists(p)) {
                steps.error "dynamicPipeline: configPath '${p}' does not exist in workspace."
            }
            return p
        }

        def found = SEARCH_PATHS.find { steps.fileExists(it) }
        if (!found) {
            steps.error "dynamicPipeline: no pipeline config file found. Tried: ${SEARCH_PATHS.join(', ')}"
        }
        return found
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private Map parse(String path) {
        if (path.endsWith('.json')) {
            return steps.readJSON(file: path) as Map
        }
        return steps.readYaml(file: path) as Map
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private void validate(Map raw, String path) {
        if (!raw.stages) {
            steps.error "dynamicPipeline: '${path}' must contain a 'stages' list."
        }

        def stageList = raw.stages as List
        stageList.eachWithIndex { s, i ->
            if (!s.name) {
                steps.error "dynamicPipeline: stage at index ${i} in '${path}' is missing required field 'name'."
            }
            def knownTypes = ['shell', 'docker', 'deploy', 'test', 'parallel', null]
            if (s.type && !knownTypes.contains(s.type)) {
                steps.echo "WARNING: stage '${s.name}' has unrecognised type '${s.type}'. It will be treated as 'shell'."
            }
        }
    }
}
