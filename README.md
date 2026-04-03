# Jenkins Pipeline Library

A **Jenkins Shared Library** that centralizes reusable pipeline steps, stages, and workflows to keep Jenkinsfiles clean, consistent, and easy to maintain.[web:66][web:69]

## Overview

This repository provides shared pipeline logic that can be loaded by multiple Jenkins jobs using the `@Library` annotation.[web:66][web:69]  
It is intended for teams that want to standardize build, test, security scanning, packaging, and deployment patterns across many services.

Key benefits:[web:67][web:70][web:73]

- DRY pipeline code (no copy-paste across Jenkinsfiles).
- Consistent stages, naming, and quality gates.
- Easier evolution of pipeline behavior from a single place.
- Clear separation between app code and pipeline/library code.

## Repository Structure

This library follows the standard Jenkins shared library layout:[web:66][web:69][web:71]

```bash
jenkins-pipeline-library/
├── README.md
├── vars/
│   ├── pipeline.groovy
│   ├── buildAndTest.groovy
│   ├── deployToK8s.groovy
│   └── ...
├── src/
│   └── org/
│       └── yourorg/
│           ├── pipeline/
│           │   ├── Build.groovy
│           │   ├── Deploy.groovy
│           │   └── Utils.groovy
│           └── ...
└── resources/
    └── org/
        └── yourorg/
            └── pipeline/
                ├── templates/
                └── config/
```

- `vars/` — global variables exposed directly in pipelines (simple entrypoints like `buildAndTest { ... }`).[web:66][web:69]
- `src/` — namespaced Groovy classes used behind those entrypoints (builders, deployers, utilities).[web:68][web:71]
- `resources/` — configuration files and templates consumed by classes or `vars` functions.[web:69][web:71]

## Configuring the Library in Jenkins

In Jenkins (Manage Jenkins → Configure System → Global Pipeline Libraries), add this repo as a shared library:[web:66][web:69][web:70]

- **Name**: `pipeline-lib` (used in `@Library('pipeline-lib')`)
- **Default version**: e.g., `main` or a specific tag (`v1.0.0`)
- **Retrieval method**: Git (point to this repository URL)
- **Load implicitly**: optional; usually keep off and load explicitly in Jenkinsfiles.

After configuration, your pipelines can load the library via:

```groovy
@Library('pipeline-lib') _
```

or with version:

```groovy
@Library('pipeline-lib@v1.0.0') _
```

## Usage Examples

### Simple usage from Jenkinsfile

```groovy
@Library('pipeline-lib') _

pipeline {
  agent any

  stages {
    stage('Build & Test') {
      steps {
        script {
          // Global var from vars/buildAndTest.groovy
          buildAndTest(
            language: 'java',
            runTests: true,
            uploadArtifacts: true
          )
        }
      }
    }

    stage('Deploy') {
      when {
        branch 'main'
      }
      steps {
        script {
          // Global var from vars/deployToK8s.groovy
          deployToK8s(
            environment: 'staging',
            namespace: 'my-app',
            chartName: 'my-app-chart'
          )
        }
      }
    }
  }
}
```

### Example global variable (vars file)

`vars/buildAndTest.groovy`:

```groovy
def call(Map args = [:]) {
  def lang = args.language ?: 'generic'

  pipeline {
    agent any

    stages {
      stage('Checkout') {
        steps {
          checkout scm
        }
      }

      stage("Build (${lang})") {
        steps {
          sh 'make build'
        }
      }

      stage('Test') {
        when {
          expression { args.get('runTests', true) }
        }
        steps {
          sh 'make test'
        }
      }
    }

    post {
      always {
        archiveArtifacts artifacts: 'build/**', fingerprint: true
      }
    }
  }
}
```

This pattern lets teams reuse the same high-level pipeline for multiple repositories while still passing options via parameters.[web:66][web:73]

## Design Guidelines

Follow these guidelines to keep the library maintainable and safe:[web:67][web:71][web:73][web:76]

- **Keep the library focused**: avoid a huge “god” library; split by domain when it grows large.
- **Use `vars/` for entrypoints** that pipelines call directly (e.g., `buildAndTest`, `deployToK8s`).
- **Use `src/` for classes** that implement reusable logic behind those entrypoints (e.g., builders, deployers, utilities).
- **Avoid overusing global variables**; keep state local or in well-scoped classes.[web:73][web:74]
- **Document parameters and behavior** in Groovy doc comments at the top of each `vars` file.
- **Version your library** (tags) and encourage pipelines to pin versions for stability.[web:66][web:69]
- **Separate library repo from application repos** so changes can be reviewed and deployed independently.[web:70]

## Development Workflow

### Local development and testing

- Write Groovy code under `vars/` and `src/`.
- Keep logic as pure and testable as possible; isolate usage of Jenkins steps.
- Consider using Gradle/Maven + unit tests for 
