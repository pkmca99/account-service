#!/usr/bin/env bash
# Removes everything account-service uses in AWS, so nothing is left running or billing:
#
#   1. EKS cluster accountsevice-cluster: node groups, add-ons, VPC, Jenkins, the app
#   2. EBS volumes of Jenkins home and the Maven cache (StorageClass gp3 keeps them: reclaim policy Retain)
#   3. Leftover IAM role jenkins-agent-ecr (normally removed with the cluster) and policy jenkins-agent-ecr
#   4. ECR repositories account-service and ci-tools, with all their images
#   5. SNS topic account-service-ci and its email subscription
#   6. EKS control-plane log group, if there is one
#
#   ./scripts/aws-down.sh          asks you to type the cluster name first
#   ./scripts/aws-down.sh --yes    no question
#
# Run from Git Bash (Windows) or any bash, with the AWS CLI signed in (aws login) and eksctl available.
# Takes about 15 minutes. Safe to re-run: what is already gone is skipped.
# It cannot be undone: images, Jenkins build history and the app's in-memory data are deleted.
# ./scripts/aws-up.sh builds everything again. Nothing outside AWS is touched (GitHub, SonarQube Cloud).
set -euo pipefail
source "$(dirname "$0")/aws-env.sh"

check_aws_account

cat <<EOF
This deletes, in AWS account $ACCOUNT_ID ($REGION):
  - EKS cluster $CLUSTER (Jenkins, the account-service app, both node groups, the VPC)
  - the Jenkins EBS volumes (build history, Maven cache)
  - IAM policy $POLICY_NAME and role $POLICY_NAME
  - ECR repositories: ${ECR_REPOS[*]} (all images)
  - SNS topic $TOPIC_NAME (build emails to $ALERT_EMAIL stop)
EOF
if [ "${1:-}" != "--yes" ]; then
    read -rp "Type the cluster name to confirm: " answer
    [ "$answer" = "$CLUSTER" ] || { echo "Not confirmed, nothing deleted."; exit 1; }
fi

# --- 1. Cluster ------------------------------------------------------------------------------
volumes=()
if cluster_exists; then
    step "Recording the EBS volumes of the cluster's persistent volumes"
    aws eks update-kubeconfig --name "$CLUSTER" --region "$REGION" >/dev/null
    while read -r vol; do
        [ -n "$vol" ] && volumes+=("$vol")
    done < <(kubectl get pv -o jsonpath='{range .items[*]}{.spec.csi.volumeHandle}{"\n"}{end}' 2>/dev/null | tr -d '\r' || true)
    echo "Volumes: ${volumes[*]:-none}"

    step "Deleting EKS cluster $CLUSTER (about 15 minutes)"
    # No eviction: nothing in the cluster is kept, so draining nodes one pod at a time only costs time
    "$EKSCTL" delete cluster --name "$CLUSTER" --region "$REGION" --wait --disable-nodegroup-eviction
else
    step "EKS cluster $CLUSTER: already gone"
fi

# --- 2. Retained EBS volumes -----------------------------------------------------------------
# Also finds volumes from an earlier, interrupted run: EBS CSI volumes made for PVCs in namespace jenkins
step "Deleting retained EBS volumes"
while read -r vol; do
    [ -n "$vol" ] && volumes+=("$vol")
done < <(aws_ ec2 describe-volumes \
    --filters Name=tag:ebs.csi.aws.com/cluster,Values=true Name=tag:kubernetes.io/created-for/pvc/namespace,Values=jenkins \
    --query 'Volumes[].VolumeId' --output text | tr '\t' '\n')

if [ ${#volumes[@]} -eq 0 ]; then
    echo "None"
else
    for vol in $(printf '%s\n' "${volumes[@]}" | sort -u); do
        if ! aws ec2 describe-volumes --volume-ids "$vol" >/dev/null 2>&1; then
            echo "$vol: already gone"
            continue
        fi
        # The node that had it attached is gone, but detaching can take a moment
        aws ec2 wait volume-available --volume-ids "$vol" || true
        aws ec2 delete-volume --volume-id "$vol" && echo "$vol: deleted"
    done
fi

# --- 3. IAM ------------------------------------------------------------------------------------
# eksctl created role jenkins-agent-ecr for the Pod Identity association and deletes it with the
# cluster. Only an interrupted run leaves it behind.
if aws iam get-role --role-name "$POLICY_NAME" >/dev/null 2>&1; then
    step "Deleting leftover IAM role $POLICY_NAME"
    for arn in $(aws_ iam list-attached-role-policies --role-name "$POLICY_NAME" --query 'AttachedPolicies[].PolicyArn' --output text); do
        aws iam detach-role-policy --role-name "$POLICY_NAME" --policy-arn "$arn"
    done
    for name in $(aws_ iam list-role-policies --role-name "$POLICY_NAME" --query 'PolicyNames[]' --output text); do
        aws iam delete-role-policy --role-name "$POLICY_NAME" --policy-name "$name"
    done
    aws iam delete-role --role-name "$POLICY_NAME"
fi

if aws iam get-policy --policy-arn "$POLICY_ARN" >/dev/null 2>&1; then
    step "Deleting IAM policy $POLICY_NAME"
    for role in $(aws_ iam list-entities-for-policy --policy-arn "$POLICY_ARN" --query 'PolicyRoles[].RoleName' --output text); do
        aws iam detach-role-policy --role-name "$role" --policy-arn "$POLICY_ARN"
    done
    for user in $(aws_ iam list-entities-for-policy --policy-arn "$POLICY_ARN" --query 'PolicyUsers[].UserName' --output text); do
        aws iam detach-user-policy --user-name "$user" --policy-arn "$POLICY_ARN"
    done
    # A policy can only be deleted once its non-default versions are gone
    for version in $(aws_ iam list-policy-versions --policy-arn "$POLICY_ARN" --query 'Versions[?!IsDefaultVersion].VersionId' --output text); do
        aws iam delete-policy-version --policy-arn "$POLICY_ARN" --version-id "$version"
    done
    aws iam delete-policy --policy-arn "$POLICY_ARN"
else
    step "IAM policy $POLICY_NAME: already gone"
fi

# --- 4. ECR ------------------------------------------------------------------------------------
for repo in "${ECR_REPOS[@]}"; do
    if aws ecr describe-repositories --repository-names "$repo" >/dev/null 2>&1; then
        step "Deleting ECR repository $repo and its images"
        aws ecr delete-repository --repository-name "$repo" --force --query 'repository.repositoryName' --output text
    else
        step "ECR repository $repo: already gone"
    fi
done

# --- 5. SNS ------------------------------------------------------------------------------------
if aws sns get-topic-attributes --topic-arn "$TOPIC_ARN" >/dev/null 2>&1; then
    step "Deleting SNS topic $TOPIC_NAME (its subscriptions go with it)"
    aws sns delete-topic --topic-arn "$TOPIC_ARN"
else
    step "SNS topic $TOPIC_NAME: already gone"
fi

# --- 6. Logs -----------------------------------------------------------------------------------
log_group=/aws/eks/$CLUSTER/cluster
if [ -n "$(aws_ logs describe-log-groups --log-group-name-prefix "$log_group" --query 'logGroups[].logGroupName' --output text)" ]; then
    step "Deleting log group $log_group"
    aws logs delete-log-group --log-group-name "$log_group"
fi

# --- Check -------------------------------------------------------------------------------------
step "Checking for leftovers"
stacks=$(aws_ cloudformation list-stacks --query "StackSummaries[?starts_with(StackName, 'eksctl-$CLUSTER') && StackStatus!='DELETE_COMPLETE'].[StackName,StackStatus]" --output text)
if [ -n "$stacks" ]; then
    echo "CloudFormation stacks not deleted (check them in the CloudFormation console):"
    echo "$stacks"
else
    echo "No eksctl CloudFormation stacks left"
fi

kubectl config get-contexts -o name 2>/dev/null | tr -d '\r' | grep -F "$CLUSTER" \
    | while read -r ctx; do kubectl config delete-context "$ctx" >/dev/null && echo "Removed kubectl context $ctx"; done || true

echo
echo "Done. Recreate everything with ./scripts/aws-up.sh"
