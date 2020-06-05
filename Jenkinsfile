#!/usr/bin/env groovy

/**
 * Copyright (C) 2019 CS-SI
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */

pipeline {
    environment {
        toolName = sh(returnStdout: true, script: "echo ${env.JOB_NAME} | cut -d '/' -f 1").trim()
        branchVersion = sh(returnStdout: true, script: "echo ${env.GIT_BRANCH} | cut -d '/' -f 2").trim()
        toolVersion = ''
        deployDirName = ''
        snapMajorVersion = ''
        sonarOption = ""
        longTestsOption = ""
    }
    agent { label 'snap-test' }
    stages {
        stage('Package and Deploy') {
            agent {
                docker {
                    label 'snap-test'
                    image 'snap-build-server.tilaa.cloud/maven:oracle'
                    args '-e MAVEN_CONFIG=/var/maven/.m2 -v /data/ssd/testData/:/data/ssd/testData/ -v /opt/maven/.m2/settings.xml:/var/maven/.m2/settings.xml -v docker_local-update-center:/local-update-center -v snap_cache:/data/cache/'
                }
            }
            steps {
                script {
                    // Get snap version from pom file
                    toolVersion = sh(returnStdout: true, script: "cat pom.xml | grep '<version>' | head -1 | cut -d '>' -f 2 | cut -d '-' -f 1 | cut -d '<' -f 1").trim()
                    snapMajorVersion = sh(returnStdout: true, script: "echo ${toolVersion} | cut -d '.' -f 1").trim()
                    deployDirName = "${toolName}/${branchVersion}-${toolVersion}-${env.GIT_COMMIT}"
                    sonarOption = ""
                    //if ("${branchVersion}" == "master") {
                    //    // Only use sonar on master branch
                    //    sonarOption = "sonar:sonar"
                    //}
                }
                echo "Build Job ${env.JOB_NAME} from ${env.GIT_BRANCH} with commit ${env.GIT_COMMIT}"
                sh "/opt/scripts/setUpUnitTestLibraries.sh"
                sh "mvn -Duser.home=/var/maven -Dsnap.userdir=/home/snap clean package install ${sonarOption} -Dsnap.reader.tests.data.dir=/data/ssd/testData/${toolName} -U -DskipTests"
                echo "Copy workspace to shared folder /data/cache/${toolName}/${env.GIT_BRANCH}/${env.BUILD_NUMBER}"
                sh "mkdir -p /data/cache/${toolName}/${env.GIT_BRANCH}/${env.BUILD_NUMBER}"
                sh "cp -R * /data/cache/${toolName}/${env.GIT_BRANCH}/${env.BUILD_NUMBER}/"
            }
            post {
                success {
                    script {
                        if ("${env.GIT_BRANCH}" == 'master' || "${env.GIT_BRANCH}" =~ /\d+\.x/ || "${env.GIT_BRANCH}" =~ /\d+\.\d+\.\d+(-rc\d+)?$/) {
                            echo "Deploy ${env.JOB_NAME} from ${env.GIT_BRANCH} with commit ${env.GIT_COMMIT}"
                            sh "mvn -Duser.home=/var/maven -Dsnap.userdir=/home/snap deploy -DskipTests=true"
                            sh "/opt/scripts/saveToLocalUpdateCenter.sh *-kit/target/netbeans_site/ ${deployDirName} ${branchVersion} ${toolName}"
                        }
                    }
                }
            }
        }
        stage('Save installer data') {
            agent {
                docker {
                    label 'snap-test'
                    image 'snap-build-server.tilaa.cloud/scripts:1.0'
                    args '-v docker_snap-installer:/snap-installer -v snap_cache:/data/cache'
                }
            }
            when {
                expression {
                    // We save snap installer data on master branch and branch x.x.x (Ex: 8.0.0) of branch x.x.x-rcx (ex: 8.0.0-rc1) when we want to create a release
                    return ("${env.GIT_BRANCH}" == 'master' || "${env.GIT_BRANCH}" =~ /\d+\.\d+\.\d+(-rc\d+)?$/);
                }
            }
            steps {
                echo "Save data for SNAP Installer ${env.JOB_NAME} from ${env.GIT_BRANCH} with commit ${env.GIT_COMMIT}"
                sh "cd /data/cache/${toolName}/${env.GIT_BRANCH}/${env.BUILD_NUMBER}/ && /opt/scripts/saveInstallData.sh ${toolName} ${env.GIT_BRANCH}"
                echo "[DATA SAVED]"
            }
            post {
                always {
                    echo "Clean-up temporary directory `/data/cache/${toolName}/${env.GIT_BRANCH}/${env.BUILD_NUMBER}/`"
                    sh "rm -R /data/cache/${toolName}/${env.GIT_BRANCH}/${env.BUILD_NUMBER}/"
                }
            }
        }
        stage('Create SNAP Installer') {
            agent { label 'snap-test' }
            when {
                expression {
                    return ("${env.GIT_BRANCH}" == 'master' || "${env.GIT_BRANCH}" =~ /\d+\.\d+\.\d+(-rc\d+)?$/);
                }
            }
            steps {
                echo "Launch snap-installer"
                build job: "snap-installer/${env.GIT_BRANCH}"
            }
        }
        stage('Create docker image') {
            agent { label 'snap-test' }
            when {
                expression {
                    return false;
                }
            }
            steps {
                echo "Launch snap-installer"
                build job: "create-snap-docker-image", parameters: [
                    [$class: 'StringParameterValue', name: 'toolName', value: "${toolName}"],
                    [$class: 'StringParameterValue', name: 'snapMajorVersion', value: "${snapMajorVersion}"],
                    [$class: 'StringParameterValue', name: 'deployDirName', value: "${deployDirName}"],
                    [$class: 'StringParameterValue', name: 'branchVersion', value: "${branchVersion}"],
                    [$class: 'BooleanParameterValue', name: 'maintenanceBranch', value: "false"]
                ],
                quietPeriod: 0,
                propagate: true,
                wait: true
            }
        }
    }
    post {
        failure {
            step (
                emailext(
                    subject: "[SNAP] JENKINS-NOTIFICATION: ${currentBuild.result ?: 'SUCCESS'} : Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'",
                    body: """Build status : ${currentBuild.result ?: 'SUCCESS'}: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]':
Check console output at ${env.BUILD_URL}
${env.JOB_NAME} [${env.BUILD_NUMBER}]""",
                    attachLog: true,
                    compressLog: true,
                    recipientProviders: [[$class: 'CulpritsRecipientProvider']]
                )
            )
        }
    }
}
