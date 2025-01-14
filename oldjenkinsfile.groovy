pipeline {
    agent any

    environment {
        VERSION = "1.0.${BUILD_NUMBER}"
        PATH = "${PATH}:${getSonarPath()}:${getDockerPath()}"
    }

    stages {
        stage ('Sonarcube Scan') {
        steps {
         script {
          scannerHome = tool 'sonarqube'
        }
        withCredentials([string(credentialsId: 'SONAR_TOKEN', variable: 'SONAR_TOKEN')]){
        withSonarQubeEnv('SonarQubeScanner') {
          sh " ${scannerHome}/bin/sonar-scanner \
          -Dsonar.projectKey=CliXX-APP-Azeez_Solola   \
          -Dsonar.login=${SONAR_TOKEN} "
        }
        }
        }

}

 stage('Quality Gate') {
            steps {
                timeout(time: 5, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
            }
            }
        }

          stage ('Build Docker Image') {
          steps {
             script{
              dockerHome= tool 'docker-inst'
            }
             //sh "${dockerHome}/bin/docker build . -t clixx-image:$VERSION "
            sh "docker build . -t clixx-image:$VERSION "
            sh "docker tag clixx-image:$VERSION clixx-image:latest"
          }
        }

    stage ('Starting Docker Image') {
          steps {
              sh '''
              if ( docker ps|grep clixx-cont ) then
                 echo "Docker image exists, killing it"
                 docker stop clixx-cont
                 docker rm clixx-cont
                 docker run --name clixx-cont  -p 80:80 -d clixx-image:$VERSION
              else
                 docker run --name clixx-cont  -p 80:80 -d clixx-image:$VERSION 
              fi
              '''
          }
    
        }

    stage ('Restore CliXX Database') {
          steps {
              sh '''
            python3 -m venv python3-virtualenv
            source python3-virtualenv/bin/activate
            python3 --version 
            pip3 install --upgrade pip
            pip3 install ansible
            pip3 install boto3 botocore boto
            ansible-playbook $WORKSPACE/deploy_db_ansible/deploy_db.yml
            deactivate

              '''
          }
        }

    stage ('Configure DB Instance') {
          steps {
            script {
                def userInput = input(id: 'confirm', message: 'Is DB creation complete?', parameters: [ [$class: 'BooleanParameterDefinition', defaultValue: false, description: 'Complete?', name: 'confirm'] ])
             }
             withCredentials([string(credentialsId: 'DB_USER_NAME', variable: 'DB_USER_NAME'),
                                 string(credentialsId: 'DB_PASSWORD', variable: 'DB_PASSWORD'),
                                 string(credentialsId: 'DB_NAME', variable: 'DB_NAME'),
                                 string(credentialsId: 'SERVER_INSTANCE', variable: 'SERVER_INSTANCE')]){
              sh '''
              USERNAME="${DB_USER_NAME}"
              PASSWORD="${DB_PASSWORD}"
              DBNAME="${DB_NAME}"
              SERVER_IP=`curl http://169.254.169.254/latest/meta-data/public-ipv4`
              SERVERINSTANCE="${SERVER_INSTANCE}"

              SERVER_IP=`curl http://169.254.169.254/latest/meta-data/public-ipv4`
              
               
       

              echo "use wordpressdb;" > ./db.setup
              echo "UPDATE wp_options SET option_value = '$SERVER_IP' WHERE option_value LIKE 'CliXX-APP-%';" >> ./db.setup
              echo "commit;" >> ./db.setup
              mysql -u "$USERNAME" --password="$PASSWORD" -h "$SERVERINSTANCE" -D "$DBNAME" < ./db.setup

            '''
            }
          }
        }

    stage ('Tear Down CliXX Docker Image and Database') {
          steps {
             script {
                def userInput = input(id: 'confirm', message: 'Tear Down Environment?', parameters: [ [$class: 'BooleanParameterDefinition', defaultValue: false, description: 'Tear Down Environment?', name: 'confirm'] ])
             }
              sh '''
            python3 -m venv python3-virtualenv
            source python3-virtualenv/bin/activate
            python3 --version
            pip3 install boto3 botocore boto
            ansible-playbook $WORKSPACE/deploy_db_ansible/delete_db.yml
            deactivate
            docker stop clixx-cont
            docker rm  clixx-cont

              '''
          }
        }


    stage ('Log Into ECR and push the newly created Docker') {
          steps {
             script {
                def userInput = input(id: 'confirm', message: 'Push Image To ECR?', parameters: [ [$class: 'BooleanParameterDefinition', defaultValue: false, description: 'Push to ECR?', name: 'confirm'] ])
             }
             withCredentials([string(credentialsId: 'ECR_REPO', variable: 'ECR_REPO')]) {
              sh '''
                ECR_URL="${ECR_REPO}"
                aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin ${ECR_URL}
                docker tag clixx-image:$VERSION ${ECR_URL}:clixx-image-$VERSION
                docker tag clixx-image:$VERSION ${ECR_URL}:latest
                
                
                docker images
                docker push ${ECR_URL}:clixx-image-$VERSION
                docker push ${ECR_URL}:latest
                
              '''
             }
          }
        }


    }
}

def getSonarPath(){
        def SonarHome= tool name: 'sonarqube', type: 'hudson.plugins.sonar.SonarRunnerInstallation'
        return SonarHome
    }
def getDockerPath(){
        def DockerHome= tool name: 'docker-inst', type: 'dockerTool'
        return DockerHome
    }
    








