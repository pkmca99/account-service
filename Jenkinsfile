// CI/CD pipeline for account-service, run by Jenkins inside the EKS cluster
//
//   Checkout -> Build & unit tests -> SonarQube analysis -> Image -> ECR -> Deploy to EKS -> Smoke test
//
// If the deploy or the smoke test fails, the deployment is rolled back to the previous version.
// Every build ends with an email (success or failure) through the SNS topic account-service-ci.
// Each build runs in its own pod (jenkins/agent-pod.yaml). No AWS keys are stored in Jenkins:
// ECR and SNS access come from EKS Pod Identity, deploy rights from a Role in namespace "accounts".
// Setup: see jenkins/README.md

// Roll the deployment back to the previous ReplicaSet. Called when deploy or smoke test fails.
def rollback() {
    container('tools') {
        sh '''
            echo "Rolling back deployment/account-service"
            kubectl -n "$K8S_NAMESPACE" rollout undo deployment/account-service || true
            kubectl -n "$K8S_NAMESPACE" rollout status deployment/account-service --timeout=300s || true
        '''
    }
}

// Remember which stage is running, so a failure email can say where the build stopped.
def stageStarted() {
    env.LAST_STAGE = env.STAGE_NAME
}

// Email the build result: publish to the SNS topic, and AWS mails every confirmed subscriber
// (pkmca99@gmail.com). The tools container publishes with the pod's Pod Identity role.
// A failed email is only logged; it never changes the build result.
def sendBuildEmail(String result) {
    try {
        def deployed = params.DEPLOY != false
        def summary
        if (result == 'SUCCESS') {
            summary = deployed ? "deployed ${env.IMAGE_TAG}" : 'tests passed, not deployed (DEPLOY unticked)'
        } else {
            summary = "stopped at ${env.LAST_STAGE ?: 'start-up'}, nothing new deployed"
        }
        def commit = env.GIT_COMMIT ? env.GIT_COMMIT.substring(0, 7) : 'n/a'
        def text = "account-service build #${env.BUILD_NUMBER}: ${result}\n" +
                "${summary}\n\n" +
                "Commit:    ${commit} ${env.COMMIT_SUBJECT ?: ''}\n" +
                "Image:     ${env.IMAGE_TAG ? env.IMAGE + ':' + env.IMAGE_TAG : 'n/a'}\n" +
                "Duration:  ${currentBuild.durationString.replace(' and counting', '')}\n" +
                "Build log: ${env.BUILD_URL}console\n" +
                "           (open after: kubectl -n jenkins port-forward svc/jenkins 8091:8080)\n" +
                "SonarQube: https://sonarcloud.io/project/overview?id=pkmca99_account-service\n"
        if (result != 'SUCCESS' && (env.LAST_STAGE == 'Deploy to EKS' || env.LAST_STAGE == 'Smoke test')) {
            text += "\nThe deployment was rolled back to the previous version automatically.\n"
        }
        def subject = "account-service #${env.BUILD_NUMBER} ${result}: ${summary}"
        if (subject.length() > 99) {
            subject = subject.substring(0, 99)        // SNS limit: 100 characters
        }
        writeFile file: 'build-email.txt', text: text
        withEnv(["EMAIL_SUBJECT=${subject}"]) {
            container('tools') {
                sh '''
                    aws sns publish --region "$AWS_REGION" --topic-arn "$ALERT_TOPIC" \
                      --subject "$EMAIL_SUBJECT" --message file://build-email.txt --query MessageId --output text
                '''
            }
        }
    } catch (err) {
        echo "Build email not sent: ${err}"
    }
}

pipeline {
    agent {
        kubernetes {
            yamlFile 'jenkins/agent-pod.yaml'
            defaultContainer 'maven'
        }
    }

    options {
        timestamps()
        timeout(time: 30, unit: 'MINUTES')
        disableConcurrentBuilds()       // also required by the ReadWriteOnce maven-repo volume
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    // Check GitHub for new commits every ~5 minutes. Switch to githubPush() once Jenkins has a public HTTPS URL.
    triggers {
        pollSCM('H/5 * * * *')
    }

    parameters {
        booleanParam(name: 'DEPLOY', defaultValue: true,
                description: 'Build the image, push it to ECR and deploy to EKS after the tests pass')
    }

    environment {
        AWS_REGION     = 'us-east-1'
        AWS_ACCOUNT_ID = '546850508021'
        ECR_REGISTRY   = "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
        IMAGE          = "${ECR_REGISTRY}/account-service"
        K8S_NAMESPACE  = 'accounts'
        ALERT_TOPIC    = "arn:aws:sns:${AWS_REGION}:${AWS_ACCOUNT_ID}:account-service-ci"   // build emails
    }

    stages {
        stage('Checkout') {
            steps {
                script { stageStarted() }
                // git lives in the agent's own jnlp container, not in the maven image
                container('jnlp') {
                    checkout scm
                    script {
                        def shortCommit = sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()
                        env.COMMIT_SUBJECT = sh(returnStdout: true, script: 'git log -1 --format=%s').trim()
                        env.IMAGE_TAG = "${env.BUILD_NUMBER}-${shortCommit}"
                        currentBuild.description = "image ${env.IMAGE_TAG}"
                    }
                }
                echo "Image will be ${IMAGE}:${IMAGE_TAG}"
            }
        }

        stage('Build & unit tests') {
            steps {
                script { stageStarted() }
                sh 'mvn -B -ntp clean verify'
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml'
                }
                success {
                    archiveArtifacts artifacts: 'target/account-service-*.jar', fingerprint: true
                }
            }
        }

        stage('SonarQube analysis') {
            steps {
                script { stageStarted() }
                // Sends code, test results and JaCoCo coverage to SonarQube Cloud, then waits for the
                // quality gate: a failed gate fails this stage, so nothing is built into an image or deployed.
                // (Waiting in the scanner avoids needing a SonarQube webhook into this private Jenkins.)
                withCredentials([string(credentialsId: 'sonarcloud-token', variable: 'SONAR_TOKEN')]) {
                    sh '''
                        mvn -B -ntp org.sonarsource.scanner.maven:sonar-maven-plugin:sonar \
                          -Dsonar.qualitygate.wait=true \
                          -Dsonar.qualitygate.timeout=300
                    '''
                }
            }
        }

        stage('Image -> ECR') {
            when { expression { params.DEPLOY != false } }   // null on the very first run = deploy
            steps {
                script { stageStarted() }
                // ECR login token (Pod Identity credentials), written as a Docker config for BuildKit
                container('tools') {
                    sh '''
                        mkdir -p .docker
                        TOKEN=$(aws ecr get-login-password --region "$AWS_REGION")
                        AUTH=$(printf 'AWS:%s' "$TOKEN" | base64 -w0)
                        printf '{"auths":{"%s":{"auth":"%s"}}}' "$ECR_REGISTRY" "$AUTH" > .docker/config.json
                    '''
                }
                // Build the Dockerfile and push this build's tag; no Docker daemon needed
                container('buildkit') {
                    sh '''
                        DOCKER_CONFIG="$WORKSPACE/.docker" buildctl-daemonless.sh build \
                          --frontend dockerfile.v0 \
                          --local context=. \
                          --local dockerfile=. \
                          --output type=image,name="$IMAGE:$IMAGE_TAG",push=true
                    '''
                }
            }
            post {
                always {
                    // Remove the ECR token; it was written as root by the tools container, so delete it there
                    container('tools') { sh 'rm -rf .docker' }
                }
            }
        }

        stage('Deploy to EKS') {
            when { expression { params.DEPLOY != false } }
            steps {
                script { stageStarted() }
                // kubectl uses the pod's service-account token (Role jenkins-deployer in namespace accounts)
                container('tools') {
                    sh '''
                        # Deploy this build's exact image tag instead of :latest
                        sed "s|$IMAGE:latest|$IMAGE:$IMAGE_TAG|" k8s.yml > k8s-deploy.yml
                        grep -q "$IMAGE:$IMAGE_TAG" k8s-deploy.yml || { echo "Image line not found in k8s.yml"; exit 1; }

                        kubectl apply -f k8s-deploy.yml
                        kubectl -n "$K8S_NAMESPACE" rollout status deployment/account-service --timeout=300s
                    '''
                }
            }
            post {
                failure {
                    script { rollback() }
                }
            }
        }

        stage('Smoke test') {
            when { expression { params.DEPLOY != false } }
            steps {
                script { stageStarted() }
                // A throwaway curl pod inside the cluster calls the service through its Kubernetes Service.
                container('tools') {
                    sh '''
                        kubectl -n "$K8S_NAMESPACE" run "smoke-test-$BUILD_NUMBER" --rm -i --restart=Never \
                          --image=curlimages/curl:8.11.1 --command -- sh -c '
                            set -e
                            curl -sf --max-time 10 http://account-service/actuator/health/readiness
                            echo
                            curl -sf --max-time 10 -o /dev/null http://account-service/api/v1/accounts
                            echo "Smoke test passed"
                          '
                    '''
                }
            }
            post {
                failure {
                    script { rollback() }
                }
            }
        }
    }

    post {
        success {
            echo "Deployed ${IMAGE}:${IMAGE_TAG}"
            script { sendBuildEmail('SUCCESS') }
        }
        failure {
            script { sendBuildEmail('FAILURE') }
        }
        unstable {
            script { sendBuildEmail('UNSTABLE') }
        }
        aborted {
            script { sendBuildEmail('ABORTED') }
        }
    }
}
