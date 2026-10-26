// CI/CD pipeline for account-service, run by Jenkins inside the EKS cluster
//
//   Checkout -> Build & unit tests -> Image -> ECR -> Deploy to EKS -> Smoke test
//
// If the deploy or the smoke test fails, the deployment is rolled back to the previous version.
// Each build runs in its own pod (jenkins/agent-pod.yaml). No AWS keys are stored in Jenkins:
// ECR access comes from EKS Pod Identity, deploy rights from a Role in namespace "accounts".
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
    }

    stages {
        stage('Checkout') {
            steps {
                // git lives in the agent's own jnlp container, not in the maven image
                container('jnlp') {
                    checkout scm
                    script {
                        def shortCommit = sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()
                        env.IMAGE_TAG = "${env.BUILD_NUMBER}-${shortCommit}"
                        currentBuild.description = "image ${env.IMAGE_TAG}"
                    }
                }
                echo "Image will be ${IMAGE}:${IMAGE_TAG}"
            }
        }

        stage('Build & unit tests') {
            steps {
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

        stage('Image -> ECR') {
            when { expression { params.DEPLOY != false } }   // null on the very first run = deploy
            steps {
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
        }
    }
}
