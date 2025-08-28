pipeline {
    agent any
    
    environment {
        DOCKERHUB_CREDENTIALS = credentials('docker-hub-credentials')
        EC2_SSH_CREDENTIALS = credentials('ec2-ssh-credentials')
        DOCKER_IMAGE = "your-dockerhub-username/flask-app:${env.BUILD_ID}"
        KUBECONFIG = credentials('kubeconfig')
    }
    
    options {
        buildDiscarder(logRotator(numToKeepStr: '5'))
        timeout(time: 30, unit: 'MINUTES')
        disableConcurrentBuilds()
    }
    
    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }
        
        stage('Setup') {
            steps {
                sh 'python --version'
                sh 'docker --version'
            }
        }
        
        stage('Lint') {
            steps {
                sh '''
                python -m pip install flake8
                flake8 . --count --select=E9,F63,F7,F82 --show-source --statistics
                flake8 . --count --exit-zero --max-complexity=10 --max-line-length=127 --statistics
                '''
            }
        }
        
        stage('Test') {
            steps {
                sh '''
                python -m pip install pytest pytest-cov
                pytest -v --cov=.
                '''
            }
        }
        
        stage('Build Docker Image') {
            steps {
                script {
                    sh "docker build -t ${DOCKER_IMAGE} ."
                }
            }
        }
        
        stage('Test Docker Image') {
            steps {
                sh """
                docker run -d --name test-container -p 5000:5000 ${DOCKER_IMAGE}
                sleep 10
                curl -f http://localhost:5000/health || exit 1
                docker stop test-container
                docker rm test-container
                """
            }
        }
        
        stage('Push to Docker Hub') {
            when {
                branch 'main'
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'docker-hub-credentials', 
                               usernameVariable: 'DOCKER_USERNAME', 
                               passwordVariable: 'DOCKER_PASSWORD')]) {
                    sh """
                    echo ${DOCKER_PASSWORD} | docker login -u ${DOCKER_USERNAME} --password-stdin
                    docker push ${DOCKER_IMAGE}
                    """
                }
            }
        }
        
        stage('Deploy to EC2') {
            when {
                branch 'main'
            }
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'ec2-ssh-credentials', 
                                keyFileVariable: 'SSH_KEY', 
                                usernameVariable: 'SSH_USERNAME')]) {
                    script {
                        def ec2Host = 'your-ec2-instance-ip'
                        sh """
                        ssh -o StrictHostKeyChecking=no -i ${SSH_KEY} ${SSH_USERNAME}@${ec2Host} << 'EOF'
                            # Pull the latest image
                            docker pull ${DOCKER_IMAGE}
                            
                            # Stop and remove old container
                            docker stop flask-app || true
                            docker rm flask-app || true
                            
                            # Run new container
                            docker run -d --name flask-app -p 80:5000 --restart unless-stopped ${DOCKER_IMAGE}
                            
                            # Clean up old images
                            docker image prune -af
                        EOF
                        """
                    }
                }
            }
        }
        
        stage('Deploy to Kubernetes') {
            when {
                branch 'main'
                expression { return params.DEPLOY_TO_K8S }
            }
            steps {
                withCredentials([file(credentialsId: 'kubeconfig', variable: 'KUBECONFIG')]) {
                    sh """
                    # Update the deployment with the new image
                    kubectl set image deployment/flask-app flask-app=${DOCKER_IMAGE} --namespace=default
                    
                    # Wait for rollout to complete
                    kubectl rollout status deployment/flask-app --namespace=default --timeout=120s
                    
                    # Verify the deployment
                    kubectl get pods --namespace=default
                    """
                }
            }
        }
    }
    
    post {
        always {
            sh '''
            # Clean up Docker containers and images
            docker stop test-container || true
            docker rm test-container || true
            docker rmi ${DOCKER_IMAGE} || true
            '''
            cleanWs()
        }
        success {
            slackSend(
                channel: '#devops-alerts',
                message: "Build Successful: ${env.JOB_NAME} ${env.BUILD_NUMBER}\nView logs: ${env.BUILD_URL}"
            )
        }
        failure {
            slackSend(
                channel: '#devops-alerts',
                message: "Build Failed: ${env.JOB_NAME} ${env.BUILD_NUMBER}\nView logs: ${env.BUILD_URL}"
            )
        }
    }
}
