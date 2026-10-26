// CI/CD pipeline for account-service
//
//   Checkout -> Build & unit tests -> Docker image -> Push to ECR -> Deploy to EKS -> Smoke test
//
// If the deploy or the smoke test fails, the deployment is rolled back to the previous version.
// Setup steps (Jenkins in Docker, AWS user, credentials, job): see jenkins/README.md

// Roll the deployment back to the previous ReplicaSet. Called when deploy or smoke test fails.
def rollback() {
    withCredentials([usernamePassword(credentialsId: 'aws-jenkins',
            usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY')]) {
        sh '''
            echo "Rolling back deployment/account-service"
            kubectl -n "$K8S_NAMESPACE" rollout undo deployment/account-service || true
            kubectl -n "$K8S_NAMESPACE" rollout status deployment/account-service --timeout=300s || true
        '''
    }
}

pipeline {
    agent any

    options {
        timestamps()
        timeout(time: 30, unit: 'MINUTES')
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    // Jenkins on a laptop can't receive GitHub webhooks, so check GitHub for new commits every ~5 minutes.
    triggers {
        pollSCM('H/5 * * * *')
    }

    parameters {
        booleanParam(name: 'DEPLOY', defaultValue: true,
                description: 'Push the image to ECR and deploy to EKS after the tests pass')
    }

    environment {
        AWS_REGION     = 'us-east-1'
        AWS_ACCOUNT_ID = '546850508021'
        ECR_REGISTRY   = "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
        IMAGE          = "${ECR_REGISTRY}/account-service"
        CLUSTER_NAME   = 'accountsevice-cluster'
        K8S_NAMESPACE  = 'accounts'
        KUBECONFIG     = "${WORKSPACE}/.kubeconfig"
        MAVEN_IMAGE    = 'maven:3.9-eclipse-temurin-25'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    def shortCommit = sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()
                    env.IMAGE_TAG = "${env.BUILD_NUMBER}-${shortCommit}"
                    currentBuild.description = "image ${env.IMAGE_TAG}"
                }
                echo "Image will be ${IMAGE}:${IMAGE_TAG}"
            }
        }

        stage('Build & unit tests') {
            steps {
                // Maven runs in its own container. --volumes-from shares the Jenkins workspace with it;
                // the maven-repo volume caches downloaded dependencies between builds.
                sh '''
                    docker run --rm \
                      --volumes-from jenkins \
                      -v maven-repo:/root/.m2 \
                      -w "$WORKSPACE" \
                      "$MAVEN_IMAGE" \
                      mvn -B -ntp clean verify
                '''
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

        stage('Docker image') {
            steps {
                sh 'docker build -t "$IMAGE:$IMAGE_TAG" -t "$IMAGE:latest" .'
            }
        }

        stage('Push to ECR') {
            when { expression { params.DEPLOY != false } }   // null on the very first run = deploy
            steps {
                withCredentials([usernamePassword(credentialsId: 'aws-jenkins',
                        usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                    sh '''
                        aws ecr get-login-password --region "$AWS_REGION" \
                          | docker login --username AWS --password-stdin "$ECR_REGISTRY"
                        docker push "$IMAGE:$IMAGE_TAG"
                        docker push "$IMAGE:latest"
                    '''
                }
            }
        }

        stage('Deploy to EKS') {
            when { expression { params.DEPLOY != false } }   // null on the very first run = deploy
            steps {
                withCredentials([usernamePassword(credentialsId: 'aws-jenkins',
                        usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                    sh '''
                        aws eks update-kubeconfig --name "$CLUSTER_NAME" --region "$AWS_REGION" --kubeconfig "$KUBECONFIG"

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
            when { expression { params.DEPLOY != false } }   // null on the very first run = deploy
            steps {
                withCredentials([usernamePassword(credentialsId: 'aws-jenkins',
                        usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                    // A throwaway curl pod inside the cluster calls the service through its Kubernetes Service.
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
        always {
            // Free disk space on the laptop: remove this build's local images and the temp kubeconfig.
            sh '''
                docker image rm "$IMAGE:$IMAGE_TAG" "$IMAGE:latest" 2>/dev/null || true
                rm -f "$KUBECONFIG" k8s-deploy.yml
            '''
        }
    }
}
