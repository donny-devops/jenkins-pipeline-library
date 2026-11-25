# Contributing

Thank you for your interest in the Jenkins Pipeline Library.

## Development setup

```bash
git clone https://github.com/donny-devops/jenkins-pipeline-library.git
cd jenkins-pipeline-library
python tests/test_groovy_syntax.py   # zero-dependency syntax and DAG checks
```

## Guidelines

- Keep Groovy classes CPS-compliant (`implements Serializable` where required).
- Prefer declarative, reusable entrypoints in `vars/`.
- Add or update examples under `examples/` when introducing new patterns.
- Ensure YAML/JSON pipeline configs remain acyclic.
- Run the local test suite and keep Gitleaks clean before opening a PR.

## Pull requests

1. Branch from `main` using `feat/`, `fix/`, `docs/`, `refactor/`, or `chore/` prefixes.
2. Update relevant documentation and examples.
3. Describe the change and any impact on existing pipelines.

By contributing you agree that your contributions are licensed under the MIT License.
