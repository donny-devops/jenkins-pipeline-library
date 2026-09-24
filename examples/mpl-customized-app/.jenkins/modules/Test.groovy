// .jenkins/modules/Test.groovy — Project-level override for MPL Test stage
def call(Map config) {
    echo "Running custom project-level test suite for Payment Service..."
    
    def testArgs = config.get('testArgs', '-v')
    def coverageMin = config.get('coverageMin', 80)
    
    sh "pytest ${testArgs} --junitxml=results.xml --cov=app --cov-fail-under=${coverageMin}"
    junit allowEmptyResults: true, testResults: 'results.xml'
    echo "Custom test module completed successfully."
}

return this
