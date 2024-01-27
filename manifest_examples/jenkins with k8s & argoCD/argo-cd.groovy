pipeline {
    agent { label 'master' }
    // agent any
    environment {
        AWS_ACCOUNT_ID="350515911022"
        AWS_DEFAULT_REGION="us-east-2"
        IMAGE_REPO_NAME="new_chatapp"
        IMAGE_TAG="""${BUILD_NUMBER}"""
        REPOSITORY_URL = "350515911022.dkr.ecr.us-east-2.amazonaws.com/new_chatapp"
        GIT_CREDENTIALS_ID = "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"
        GIT_REPO_URL = "https://github.com/chaitanya1330/new_chatapp.git"
    }
   
    stages {
	
        stage('git') {
            steps {
                // Get some code from a GitHub repository
                git url: 'https://github.com/chaitanya1330/new_chatapp.git', branch: 'master'
            }
        }
        
       


         stage('Logging into AWS ECR') {
            steps {
                script {
                sh """aws ecr get-login-password --region ${AWS_DEFAULT_REGION} | docker login --username AWS --password-stdin ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_DEFAULT_REGION}.amazonaws.com"""

                }
            }
        }
        
        
  
        // Building Docker images
        stage('Building image') {
          steps{
            script {
              dockerImage = docker.build "${IMAGE_REPO_NAME}:${IMAGE_TAG}"
            }
          }
        }
   
        // Uploading Docker images into AWS ECR
        stage('Pushing to ECR') {
         steps{  
             script {
                    sh """docker tag ${IMAGE_REPO_NAME}:${IMAGE_TAG} ${REPOSITORY_URL}:$IMAGE_TAG"""
                    sh """docker push ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_DEFAULT_REGION}.amazonaws.com/${IMAGE_REPO_NAME}:${IMAGE_TAG}"""
                }
            }
        }
        

        stage('Checkout K8S manifest SCM'){
            steps {
                git credentialsId: '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', 
                url: 'https://github.com/chaitanya1330/new_chatapp.git',
                branch: 'master'
            }
        }

        stage('Update Kubernetes Manifest and Push to Git') {
            agent {
                label 'kubernetes'
            }
            steps {
                script {
                    withCredentials([usernamePassword(credentialsId: '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', passwordVariable: 'Chaitu@8179151958', usernameVariable: 'chaitanya1330')]) {
                        // Updated the Kubernetes manifest file with the new image tag
                        sh """
                        cat backend.yml
                        sed -i 's|image: REPLACE_ME|image: ${REPOSITORY_URL}:${IMAGE_TAG}|' backend.yml
                        cat backend.yml
                        git add backend.yml
                        git commit -m 'Updated Kubernetes manifest with new image tag | Jenkins Pipeline'
                        git push ${GIT_CREDENTIALS_ID} ${GIT_REPO_URL} HEAD:master
                        """
                    }
                }
            }
        }
     
        
    }
}
        