# Shared settings for aws-up.sh and aws-down.sh (sourced, not run).
# They must match cluster.yml, the Jenkinsfile and the files in jenkins/.

CLUSTER=accountsevice-cluster
REGION=us-east-1
ACCOUNT_ID=546850508021
ECR_REPOS=(account-service ci-tools)
CI_TOOLS_IMAGE=$ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com/ci-tools:1
TOPIC_NAME=account-service-ci                 # build emails (Jenkinsfile: ALERT_TOPIC)
TOPIC_ARN=arn:aws:sns:$REGION:$ACCOUNT_ID:$TOPIC_NAME
ALERT_EMAIL=pkmca99@gmail.com
POLICY_NAME=jenkins-agent-ecr                 # IAM policy, and the Pod Identity role of the build agents
POLICY_ARN=arn:aws:iam::$ACCOUNT_ID:policy/$POLICY_NAME
JENKINS_CHART_VERSION=5.10.0

export MSYS_NO_PATHCONV=1                     # Git Bash: don't turn /aws/... arguments into Windows paths
export AWS_REGION=$REGION AWS_PAGER=""

# Run from the project folder, whatever the current directory was
cd "$(dirname "${BASH_SOURCE[0]}")/.."

# eksctl.exe in the project folder, else eksctl on the PATH; helm on the PATH unless HELM is set
if [ -x ./eksctl.exe ]; then EKSCTL=./eksctl.exe; else EKSCTL=eksctl; fi
HELM=${HELM:-helm}

# Windows builds of aws and kubectl print CRLF; strip the CR so values compare cleanly
aws_() { aws "$@" | tr -d '\r'; }

step() { printf '\n==> %s\n' "$*"; }

# Stop unless the AWS CLI is signed in to the right account
check_aws_account() {
    local account
    account=$(aws_ sts get-caller-identity --query Account --output text) \
        || { echo "AWS CLI is not signed in. Run: aws login" >&2; exit 1; }
    if [ "$account" != "$ACCOUNT_ID" ]; then
        echo "Signed in to AWS account $account, expected $ACCOUNT_ID. Stopping." >&2
        exit 1
    fi
}

cluster_exists() { aws eks describe-cluster --name "$CLUSTER" >/dev/null 2>&1; }
