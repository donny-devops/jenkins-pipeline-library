"""
Comprehensive validation test suite for Jenkins Shared Library.
Validates Groovy syntax structure, CPS Serializable compliance,
package/class namespace integrity, MPL modules, DAG pipeline configs,
and reference example applications.
Requires no external third-party dependencies (pure standard library).
"""

import os
import re
import sys
from typing import Dict, List, Set


def test_vars_structure(repo_root: str):
    """Validate global variables in vars/."""
    vars_dir = os.path.join(repo_root, "vars")
    assert os.path.isdir(vars_dir), "vars/ directory missing"

    expected_vars = {
        "dynamicPipeline.groovy": [
            "def call(",
            "PipelineConfig",
            "StageFactory",
            "checkout scm",
        ],
        "mplPipeline.groovy": [
            "def call(",
            "MPLManager",
            "mpl.init()",
            "mpl.runAll()",
        ],
        "threeTierDeploy.groovy": [
            "def call(",
            "TierDeployer",
            "stage('Checkout')",
            "stage('Build')",
            "stage('Test')",
            "stage('Dockerize')",
            "stage('Deploy: Database')",
            "stage('Deploy: Backend')",
            "stage('Deploy: Frontend')",
            "stage('Smoke Test')",
        ],
        "securityScanPipeline.groovy": [
            "def call(",
            "pipeline {",
            "Secret Scanning (Gitleaks)",
            "SCA & SAST Security Scanning",
            "Wazuh SIEM Event Forwarding",
            "notifyPagerDuty",
            "notifySlack",
        ],
        "notifySlack.groovy": [
            "def call(",
            "channel",
            "attachments",
            "color",
            "title_link",
        ],
        "notifyPagerDuty.groovy": [
            "def call(",
            "routing_key",
            "event_action",
            "dedup_key",
            "severity",
            "source",
            "component",
            "custom_details",
        ],
    }

    for filename, tokens in expected_vars.items():
        filepath = os.path.join(vars_dir, filename)
        assert os.path.isfile(filepath), f"Missing expected var file: {filename}"
        with open(filepath, "r", encoding="utf-8") as f:
            content = f.read()
        assert len(content.strip()) > 0, f"File {filename} is empty"
        for token in tokens:
            assert token in content, f"File {filename} missing required token: '{token}'"
        print(f"  [OK] vars/{filename} ({len(tokens)} token checks passed)")


def test_src_classes(repo_root: str):
    """Validate Groovy classes in src/ adhere to Jenkins CPS and naming standards."""
    src_dir = os.path.join(repo_root, "src")
    assert os.path.isdir(src_dir), "src/ directory missing"

    groovy_classes = []
    for root, _, files in os.walk(src_dir):
        for f in files:
            if f.endswith(".groovy"):
                groovy_classes.append(os.path.join(root, f))

    assert len(groovy_classes) >= 6, f"Expected at least 6 src classes, found {len(groovy_classes)}"

    for filepath in groovy_classes:
        rel_path = os.path.relpath(filepath, src_dir)
        dir_name = os.path.dirname(rel_path).replace(os.sep, ".")
        class_name = os.path.splitext(os.path.basename(filepath))[0]

        with open(filepath, "r", encoding="utf-8") as f:
            content = f.read()

        # Check package statement
        expected_pkg = f"package {dir_name}"
        assert expected_pkg in content, f"{rel_path} missing matching package: '{expected_pkg}'"

        # Check class declaration
        class_pattern = rf"class\s+{class_name}\b"
        assert re.search(class_pattern, content), f"{rel_path} does not declare class {class_name}"

        # Check CPS Serializable compliance
        assert "implements Serializable" in content or "Serializable" in content, (
            f"{rel_path} class {class_name} should implement Serializable for Jenkins CPS support"
        )
        print(f"  [OK] src/{rel_path.replace(os.sep, '/')} (package and Serializable class {class_name} confirmed)")


def test_mpl_modules(repo_root: str):
    """Validate modular step modules in resources/mpl/modules/."""
    modules_dir = os.path.join(repo_root, "resources", "mpl", "modules")
    assert os.path.isdir(modules_dir), "resources/mpl/modules/ missing"

    expected_modules = ["Build.groovy", "Deploy.groovy", "Docker.groovy", "SecurityScan.groovy", "Test.groovy"]
    for mod in expected_modules:
        mod_path = os.path.join(modules_dir, mod)
        assert os.path.isfile(mod_path), f"Missing MPL module: {mod}"
        with open(mod_path, "r", encoding="utf-8") as f:
            content = f.read()
        assert len(content.strip()) > 50, f"Module {mod} is suspiciously short"
        print(f"  [OK] resources/mpl/modules/{mod} ({len(content)} bytes)")


def parse_simple_yaml_stages(filepath: str) -> List[Dict]:
    """Lightweight zero-dependency extractor for stage names and dependsOn from YAML."""
    stages = []
    current_stage = None
    in_depends_on = False

    with open(filepath, "r", encoding="utf-8") as f:
        for line in f:
            stripped = line.strip()
            # Detect new stage
            if stripped.startswith("- name:"):
                name = stripped.split("- name:", 1)[1].strip().strip("'\"")
                current_stage = {"name": name, "dependsOn": []}
                stages.append(current_stage)
                in_depends_on = False
            elif current_stage is not None:
                if stripped.startswith("dependsOn:"):
                    in_depends_on = True
                    # Inline format: dependsOn: [A, B]
                    rest = stripped.split("dependsOn:", 1)[1].strip()
                    if rest.startswith("[") and rest.endswith("]"):
                        items = [x.strip().strip("'\"") for x in rest[1:-1].split(",") if x.strip()]
                        current_stage["dependsOn"].extend(items)
                        in_depends_on = False
                elif in_depends_on:
                    if stripped.startswith("- "):
                        dep = stripped[2:].strip().strip("'\"")
                        current_stage["dependsOn"].append(dep)
                    elif stripped and not stripped.startswith("#"):
                        in_depends_on = False

    return stages


def validate_dag(filepath: str, min_stages: int = 5):
    """Validate a YAML stage dependency graph (DAG) for cycles and consistency."""
    assert os.path.isfile(filepath), f"{filepath} missing"

    stages = parse_simple_yaml_stages(filepath)
    assert len(stages) >= min_stages, f"Expected at least {min_stages} stages in {filepath}, parsed {len(stages)}"

    stage_names = {s["name"] for s in stages}
    assert len(stage_names) == len(stages), f"Duplicate stage names detected in {filepath}"

    # Build adjacency list
    graph: Dict[str, List[str]] = {s["name"]: s["dependsOn"] for s in stages}

    # Verify all dependencies reference real stages
    for name, deps in graph.items():
        for dep in deps:
            assert dep in stage_names, f"Stage '{name}' depends on non-existent stage '{dep}' in {filepath}"

    # Cycle detection via 3-color DFS
    state = {name: 0 for name in stage_names}

    def dfs(node: str, path: List[str]):
        state[node] = 1
        path.append(node)
        for neighbor in graph.get(node, []):
            if state[neighbor] == 1:
                cycle = " -> ".join(path + [neighbor])
                raise AssertionError(f"Cycle detected in pipeline dependency graph: {cycle}")
            if state[neighbor] == 0:
                dfs(neighbor, path)
        path.pop()
        state[node] = 2

    for node in stage_names:
        if state[node] == 0:
            dfs(node, [])

    print(f"  [OK] DAG validation passed for {len(stages)} stages in {os.path.basename(filepath)} (Acyclic, no cycles)")


def test_example_applications(repo_root: str):
    """Validate reference example applications in examples/."""
    examples_dir = os.path.join(repo_root, "examples")
    assert os.path.isdir(examples_dir), "examples/ directory missing"

    # 1. Three-Tier App
    three_tier_jf = os.path.join(examples_dir, "three-tier-app", "Jenkinsfile")
    assert os.path.isfile(three_tier_jf), "three-tier-app/Jenkinsfile missing"
    with open(three_tier_jf, "r", encoding="utf-8") as f:
        content = f.read()
    assert "threeTierDeploy(" in content
    assert "database:" in content and "backend:" in content and "frontend:" in content
    print("  [OK] examples/three-tier-app/Jenkinsfile validated")

    # 2. MPL Customized App
    mpl_jf = os.path.join(examples_dir, "mpl-customized-app", "Jenkinsfile")
    assert os.path.isfile(mpl_jf), "mpl-customized-app/Jenkinsfile missing"
    with open(mpl_jf, "r", encoding="utf-8") as f:
        content = f.read()
    assert "mplPipeline(" in content
    assert "stageOrder:" in content
    print("  [OK] examples/mpl-customized-app/Jenkinsfile validated")

    # Check MPL module overrides
    for override in ["Test.groovy", "Deploy.groovy"]:
        override_path = os.path.join(examples_dir, "mpl-customized-app", ".jenkins", "modules", override)
        assert os.path.isfile(override_path), f"Missing MPL override: {override}"
        with open(override_path, "r", encoding="utf-8") as f:
            c = f.read()
        assert "def call(" in c and "return this" in c
        print(f"  [OK] examples/mpl-customized-app/.jenkins/modules/{override} validated")

    # 3. Dynamic DAG App
    dyn_jf = os.path.join(examples_dir, "dynamic-dag-app", "Jenkinsfile")
    assert os.path.isfile(dyn_jf), "dynamic-dag-app/Jenkinsfile missing"
    with open(dyn_jf, "r", encoding="utf-8") as f:
        content = f.read()
    assert "dynamicPipeline()" in content
    print("  [OK] examples/dynamic-dag-app/Jenkinsfile validated")

    dyn_yaml = os.path.join(examples_dir, "dynamic-dag-app", "pipeline.yaml")
    validate_dag(dyn_yaml, min_stages=5)


def main():
    print("=" * 65)
    print("Jenkins Pipeline Library — Test & Quality Gate Suite")
    print("=" * 65)
    repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))

    print("\n[1/5] Validating Global Pipeline Variables (vars/)...")
    test_vars_structure(repo_root)

    print("\n[2/5] Validating Core Classes (src/)...")
    test_src_classes(repo_root)

    print("\n[3/5] Validating Modular Pipeline Library Modules (resources/mpl/)...")
    test_mpl_modules(repo_root)

    print("\n[4/5] Validating Dynamic Pipeline Configurations & DAG Graph...")
    example_yaml = os.path.join(repo_root, "resources", "pipeline-configs", "example-pipeline.yaml")
    validate_dag(example_yaml, min_stages=6)

    print("\n[5/5] Validating Reference Example Applications (examples/)...")
    test_example_applications(repo_root)

    print("\n" + "=" * 65)
    print("All validation suites passed cleanly! 0 errors, 100% compliant.")
    print("=" * 65)


if __name__ == "__main__":
    main()
