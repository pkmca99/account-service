#!/usr/bin/env bash
# Builds everything account-service uses in AWS, from nothing (the reverse of aws-down.sh):
#
#   1. EKS cluster from cluster.yml: node groups dev-nodes (app) and ci-nodes (Jenkins)
#   2. Add-ons Pod Identity agent and EBS CSI driver, both pinned to ci-nodes
#   3. StorageClass gp3, namespaces accounts and jenkins, Maven cache PVC, deploy RBAC
#   4. ECR repositories account-service and ci-tools
#   5. IAM policy jenkins-agent-ecr and the Pod Identity association of the build agents
#   6. SNS topic account-service-ci with an email subscription for the build emails
#   7. The ci-tools image (aws + kubectl for the build pods)
#   8. Secret sonarcloud-token (you type the token; it is never shown or stored in a file)
#   9. Jenkins (Helm), which comes up with the account-service job
#
#   ./scripts/aws-up.sh
#
# Needs, in Git Bash (Windows) or any bash: the AWS CLI signed in (aws login), eksctl, kubectl,
# helm (or HELM=/path/to/helm) and Docker running. Takes about 30 minutes.
# Safe to re-run: what already exists is kept.
# The app itself is deployed by the first Jenkins build (see the end of this script's output).
set -euo pipefail
source "$(dirname "$0")/aws-env.sh"

for tool in aws "$EKSCTL" kubectl "$HELM" docker; do
    command -v "$tool" >/dev/null || { echo "Missing tool: $tool" >&2; exit 1; }
done
check_aws_account

# --- 1. Cluster ------------------------------------------------------------------------------
if cluster_exists; then
    step "EKS cluster $CLUSTER exists; adding any missing node group"
    aws eks update-kubeconfig --name "$CLUSTER" --region "$REGION" >/dev/null
    "$EKSCTL" create nodegroup --config-file cluster.yml     # node groups that already exist are skipped
else
    step "Creating EKS cluster $CLUSTER (about 20 minutes)"
    "$EKSCTL" create cluster --config-file cluster.yml        # also writes the kubectl context
fi

# --- 2. Add-ons --------------------------------------------------------------------------------
# Both run only on ci-nodes: dev-nodes (t3a.small) holds 8 pods and the app needs the free slots.
# Once, the EBS CSI DaemonSet landed there and preempted the account-service pod.
ci_only='{"nodeSelector":{"workload":"ci"},"tolerations":[{"key":"workload","operator":"Equal","value":"ci","effect":"NoSchedule"}]}'
declare -A addon_config=(
    [eks-pod-identity-agent]=$ci_only
    [aws-ebs-csi-driver]="{\"node\":$ci_only,\"controller\":$ci_only}"
)
for addon in eks-pod-identity-agent aws-ebs-csi-driver; do
    step "Add-on $addon"
    if ! aws eks describe-addon --cluster-name "$CLUSTER" --addon-name "$addon" >/dev/null 2>&1; then
        # For the EBS CSI driver, eksctl also creates its IAM role (IRSA)
        "$EKSCTL" create addon --cluster "$CLUSTER" --region "$REGION" --name "$addon" --wait
    fi
    # Keep the IAM role when changing the configuration; without it the service account loses its annotation
    role=$(aws_ eks describe-addon --cluster-name "$CLUSTER" --addon-name "$addon" --query 'addon.serviceAccountRoleArn' --output text)
    role_arg=()
    [ -n "$role" ] && [ "$role" != "None" ] && role_arg=(--service-account-role-arn "$role")
    current=$(aws_ eks describe-addon --cluster-name "$CLUSTER" --addon-name "$addon" --query 'addon.configurationValues' --output text)
    if [ "$current" != "${addon_config[$addon]}" ]; then
        aws eks update-addon --cluster-name "$CLUSTER" --addon-name "$addon" "${role_arg[@]}" \
            --configuration-values "${addon_config[$addon]}" --resolve-conflicts OVERWRITE \
            --query 'update.status' --output text
        aws eks wait addon-active --cluster-name "$CLUSTER" --addon-name "$addon"
    fi
    echo "Running on ci-nodes"
done

# --- 3. Kubernetes basics ----------------------------------------------------------------------
step "StorageClass, namespaces, Maven cache, deploy RBAC"
kubectl apply -f jenkins/k8s/storageclass.yml
for ns in accounts jenkins; do
    kubectl create namespace "$ns" --dry-run=client -o yaml | kubectl apply -f -
done
kubectl apply -f jenkins/k8s/maven-repo-pvc.yml
kubectl apply -f jenkins/k8s/deployer-rbac.yml

# --- 4. ECR ------------------------------------------------------------------------------------
for repo in "${ECR_REPOS[@]}"; do
    step "ECR repository $repo"
    if aws ecr describe-repositories --repository-names "$repo" >/dev/null 2>&1; then
        echo "Exists"
    else
        aws ecr create-repository --repository-name "$repo" --image-scanning-configuration scanOnPush=true \
            --query 'repository.repositoryUri' --output text
    fi
done

# --- 5. IAM for the build agents ---------------------------------------------------------------
step "IAM policy $POLICY_NAME"
if aws iam get-policy --policy-arn "$POLICY_ARN" >/dev/null 2>&1; then
    echo "Exists (to change it: jenkins/README.md, 'Change the setup')"
else
    aws iam create-policy --policy-name "$POLICY_NAME" --policy-document file://jenkins/iam/jenkins-agent-ecr-policy.json \
        --query 'Policy.Arn' --output text
fi

step "Pod Identity: service account jenkins/jenkins-agent -> role $POLICY_NAME"
existing=$(aws_ eks list-pod-identity-associations --cluster-name "$CLUSTER" --namespace jenkins \
    --service-account jenkins-agent --query 'associations[].associationId' --output text)
if [ -n "$existing" ]; then
    echo "Exists"
else
    "$EKSCTL" create podidentityassociation --cluster "$CLUSTER" --region "$REGION" \
        --namespace jenkins --service-account-name jenkins-agent \
        --role-name "$POLICY_NAME" --permission-policy-arns "$POLICY_ARN"
fi

# --- 6. Build emails ---------------------------------------------------------------------------
step "SNS topic $TOPIC_NAME, email to $ALERT_EMAIL"
aws sns create-topic --name "$TOPIC_NAME" --query TopicArn --output text      # returns the existing one if present
subscribed=$(aws_ sns list-subscriptions-by-topic --topic-arn "$TOPIC_ARN" \
    --query "Subscriptions[?Endpoint=='$ALERT_EMAIL'].SubscriptionArn" --output text)
if [ -z "$subscribed" ]; then
    aws sns subscribe --topic-arn "$TOPIC_ARN" --protocol email --notification-endpoint "$ALERT_EMAIL" >/dev/null
    subscribed="PendingConfirmation"
fi
if [ "$subscribed" = "PendingConfirmation" ]; then
    echo "ACTION: open the 'AWS Notification - Subscription Confirmation' email in $ALERT_EMAIL and click 'Confirm subscription'"
else
    echo "Subscription confirmed"
fi

# --- 7. ci-tools image -------------------------------------------------------------------------
step "Image $CI_TOOLS_IMAGE"
if aws ecr describe-images --repository-name ci-tools --image-ids imageTag="${CI_TOOLS_IMAGE##*:}" >/dev/null 2>&1; then
    echo "Exists"
else
    aws ecr get-login-password | docker login --username AWS --password-stdin "${CI_TOOLS_IMAGE%%/*}"
    docker build -t "$CI_TOOLS_IMAGE" jenkins/ci-tools
    docker push "$CI_TOOLS_IMAGE"
fi

# --- 8. SonarQube Cloud token ------------------------------------------------------------------
step "Secret jenkins/sonarcloud-token"
if kubectl -n jenkins get secret sonarcloud-token >/dev/null 2>&1; then
    echo "Exists"
else
    echo "Paste a SonarQube Cloud token (sonarcloud.io > My Account > Security). It is not shown."
    read -rsp "Token: " token; echo
    [ -n "$token" ] || { echo "No token given. Re-run the script to continue." >&2; exit 1; }
    # printf is a shell builtin: the token never appears on a command line or in a file
    printf 'apiVersion: v1\nkind: Secret\nmetadata:\n  name: sonarcloud-token\n  namespace: jenkins\ntype: Opaque\ndata:\n  token: %s\n' \
        "$(printf '%s' "$token" | base64 | tr -d '\n')" | kubectl apply -f -
    unset token
fi

# --- 9. Jenkins --------------------------------------------------------------------------------
step "Jenkins (Helm chart $JENKINS_CHART_VERSION, about 5 minutes)"
"$HELM" repo add jenkins https://charts.jenkins.io --force-update >/dev/null
"$HELM" upgrade --install jenkins jenkins/jenkins -n jenkins -f jenkins/helm/values.yaml \
    --version "$JENKINS_CHART_VERSION" --wait --timeout 15m

cat <<'EOF'

Done. Next:
  1. Open Jenkins:   kubectl -n jenkins port-forward svc/jenkins 8091:8080   ->  http://localhost:8091
     User admin, password: kubectl -n jenkins get secret jenkins -o jsonpath="{.data.jenkins-admin-password}" | base64 -d
  2. Job account-service > Build with Parameters > Build. This first build pushes the image to ECR and
     deploys the app to namespace accounts. After that, every push to main builds and deploys by itself.
  3. If not done yet, confirm the SNS subscription email, or the build emails won't arrive.
EOF
