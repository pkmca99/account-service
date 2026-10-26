# Jenkins CI/CD for account-service (on EKS)

Jenkins runs inside the EKS cluster `accountsevice-cluster`. Every push to GitHub runs the pipeline in the
`Jenkinsfile` at the project root:

```
GitHub ──► Checkout ──► Build & unit tests ──► Image -> ECR ──► Deploy to EKS ──► Smoke test
           (jnlp)        (maven container,      (BuildKit,       (kubectl apply,   (curl pod: health
                          mvn verify, JUnit)     no Docker)       rollout status)    + accounts API)
                                                                       │                  │
                                                                       └── fails? ──► automatic rollback
```

## What runs where

| Part | Where | Defined in |
|---|---|---|
| Jenkins controller | Pod `jenkins-0`, namespace `jenkins`, on node group `ci-nodes` (t3.large on-demand, us-east-1a, tainted `workload=ci`) | `helm/values.yaml` |
| Jenkins home | 20 GiB gp3 EBS volume (PVC `jenkins`, StorageClass `gp3`, reclaim policy `Retain`) | `k8s/storageclass.yml` |
| Plugins, job `account-service` | Configuration as code (JCasC + Job DSL) in the Helm values | `helm/values.yaml` |
| Build agent | One pod per build: `jnlp` (git), `maven`, `buildkit`, `tools` (aws + kubectl) | `agent-pod.yaml` |
| `tools` image | `546850508021.dkr.ecr.us-east-1.amazonaws.com/ci-tools:1` | `ci-tools/Dockerfile` |
| Maven cache | PVC `maven-repo`, 5 GiB gp3 | `k8s/maven-repo-pvc.yml` |
| ECR push permission | EKS Pod Identity: service account `jenkins/jenkins-agent` → IAM role `jenkins-agent-ecr` → policy `jenkins-agent-ecr` (push to `account-service` and `ci-tools` only) | `iam/jenkins-agent-ecr-policy.json` |
| Deploy permission | Role `jenkins-deployer` in namespace `accounts` only | `k8s/deployer-rbac.yml` |
| The app | Node group `dev-nodes` (Spot t3a.small), namespace `accounts` | `../k8s.yml` |

No AWS access key is stored in Jenkins. The agents get short-lived AWS credentials from Pod Identity, and
kubectl uses the pod's service-account token.

All commands below work in PowerShell or CMD from `C:\Users\pkmca\Downloads\account-service`.
They need the AWS CLI signed in, `kubectl`, `helm` (`winget install Helm.Helm`) and `.\eksctl.exe`.

---

## Daily use

### Open Jenkins

```
kubectl -n jenkins port-forward svc/jenkins 8091:8080
```

Open **http://localhost:8091**. User `admin`; the password is in a Kubernetes secret:

```
kubectl -n jenkins get secret jenkins -o jsonpath="{.data.jenkins-admin-password}"
```

That prints base64; decode it with PowerShell:
`[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String("<value>"))`.


### Run the pipeline

Push to `main`; Jenkins checks GitHub every 5 minutes. Or click **Build with Parameters** on the
`account-service` job. Untick `DEPLOY` to build and test only.

Watch the agent pod start and finish:

```
kubectl -n jenkins get pods -w
```

### Check the deployment

```
kubectl -n accounts get deployment account-service -o jsonpath="{.spec.template.spec.containers[0].image}"
kubectl -n accounts rollout history deployment/account-service
kubectl -n accounts rollout undo deployment/account-service        # manual rollback
```

### Save money between practice sessions

The `ci-nodes` node costs about $61/month if it runs all the time. Scale it to zero when you are done;
Jenkins waits as `Pending` and its EBS volume is kept:

```
.\eksctl.exe scale nodegroup --cluster accountsevice-cluster --region us-east-1 --name ci-nodes --nodes 0
.\eksctl.exe scale nodegroup --cluster accountsevice-cluster --region us-east-1 --name ci-nodes --nodes 1
```

The EBS CSI driver and the Pod Identity agent also run only on `ci-nodes`, so while it is at zero,
nothing can create EBS volumes or use Pod Identity. The app itself is not affected.

---

## Change the setup

| To change | Edit | Apply with |
|---|---|---|
| Plugins, job definition, controller size | `helm/values.yaml` | `helm upgrade --install jenkins jenkins/jenkins -n jenkins -f jenkins/helm/values.yaml --version 5.10.0` |
| Build containers, their sizes | `agent-pod.yaml` | Commit and push; the next build uses it |
| aws / kubectl versions | `ci-tools/Dockerfile` | Build and push a new tag (commands in the file), then update `agent-pod.yaml` |
| What the pipeline may deploy | `k8s/deployer-rbac.yml` | `kubectl apply -f jenkins/k8s/deployer-rbac.yml` |
| Which ECR repositories it may push to | `iam/jenkins-agent-ecr-policy.json` | `aws iam create-policy-version --policy-arn arn:aws:iam::546850508021:policy/jenkins-agent-ecr --policy-document file://jenkins/iam/jenkins-agent-ecr-policy.json --set-as-default` |

Settings made by clicking in the Jenkins UI are lost when the controller restarts with a new
configuration; put them in `helm/values.yaml` instead.

---

## Set up from scratch

This is what was run to build the current setup, in order. Every step is safe to re-run except where noted.

**1. CI node group** (about 5 minutes):

```
.\eksctl.exe create nodegroup --config-file cluster.yml --include ci-nodes
```

**2. Add-ons: Pod Identity agent and EBS CSI driver.** Both are pinned to `ci-nodes`. The `dev-nodes`
t3a.small can only run 8 pods and is nearly full; when the EBS CSI DaemonSet first landed there, its
system-critical pod *preempted the account-service pod*.

```
.\eksctl.exe create addon --cluster accountsevice-cluster --region us-east-1 --name eks-pod-identity-agent
.\eksctl.exe create addon --cluster accountsevice-cluster --region us-east-1 --name aws-ebs-csi-driver
```

Then pin both to `ci-nodes`. Save this as `ci-nodes-only.json`:

```json
{"nodeSelector":{"workload":"ci"},"tolerations":[{"key":"workload","operator":"Equal","value":"ci","effect":"NoSchedule"}]}
```

and this as `ebs-ci-nodes-only.json`:

```json
{"node":{"nodeSelector":{"workload":"ci"},"tolerations":[{"key":"workload","operator":"Equal","value":"ci","effect":"NoSchedule"}]},
 "controller":{"nodeSelector":{"workload":"ci"},"tolerations":[{"key":"workload","operator":"Equal","value":"ci","effect":"NoSchedule"}]}}
```

```
aws eks update-addon --cluster-name accountsevice-cluster --region us-east-1 --addon-name eks-pod-identity-agent --configuration-values file://ci-nodes-only.json --resolve-conflicts OVERWRITE
aws eks update-addon --cluster-name accountsevice-cluster --region us-east-1 --addon-name aws-ebs-csi-driver --configuration-values file://ebs-ci-nodes-only.json --resolve-conflicts OVERWRITE
```

The EBS CSI driver uses the IRSA role eksctl created with the add-on
(`eksctl-accountsevice-cluster-addon-aws-ebs-cs-Role1-…`). Check that its service account still has the role:

```
kubectl -n kube-system get sa ebs-csi-controller-sa -o jsonpath="{.metadata.annotations}"
```

**3. Storage, namespace, RBAC:**

```
kubectl apply -f jenkins/k8s/storageclass.yml
kubectl create namespace jenkins
kubectl apply -f jenkins/k8s/maven-repo-pvc.yml
kubectl apply -f jenkins/k8s/deployer-rbac.yml
```

**4. AWS permissions for the build agents:**

```
aws ecr create-repository --repository-name ci-tools --region us-east-1 --image-scanning-configuration scanOnPush=true
aws iam create-policy --policy-name jenkins-agent-ecr --policy-document file://jenkins/iam/jenkins-agent-ecr-policy.json
.\eksctl.exe create podidentityassociation --cluster accountsevice-cluster --region us-east-1 --namespace jenkins --service-account-name jenkins-agent --role-name jenkins-agent-ecr --permission-policy-arns arn:aws:iam::546850508021:policy/jenkins-agent-ecr
```

**5. The `ci-tools` image** — commands at the top of `ci-tools/Dockerfile`. In PowerShell 5.1, run the
`docker login` line from Git Bash; piping the ECR password through PowerShell 5.1 corrupts it (`400 Bad Request`).

**6. Jenkins:**

```
helm repo add jenkins https://charts.jenkins.io
helm upgrade --install jenkins jenkins/jenkins -n jenkins -f jenkins/helm/values.yaml --version 5.10.0 --wait
```

**7. Point the job at the GitHub repository**: set the URL in `helm/values.yaml`
(`remote { url('https://github.com/<user>/account-service.git') }`) and run step 6 again. For a private
repository, also add a GitHub token credential (see Troubleshooting).

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Agent pod stays `Pending` | `ci-nodes` scaled to 0, or the build needs more memory than the node has: `kubectl -n jenkins describe pod <agent-pod>` |
| Deploy times out at `rollout status`, new app pod `Pending`, event `Too many pods` | `dev-nodes` (t3a.small) allows 8 pods and normally runs 7. A rolling update or the smoke-test pod needs the 8th slot. Check nothing extra landed there: `kubectl get pods -A --field-selector spec.nodeName=<dev node>` |
| `AccessDenied` from `aws` or BuildKit push | Pod Identity association missing or policy too narrow: `aws eks list-pod-identity-associations --cluster-name accountsevice-cluster --region us-east-1` |
| `Forbidden` from kubectl | Role `jenkins-deployer` only covers namespace `accounts`; `k8s.yml` must not contain cluster-wide objects |
| BuildKit `failed to create ... namespace` / permission errors | The `buildkit` container needs the `Unconfined` seccomp and AppArmor settings from `agent-pod.yaml` |
| Checkout fails with `could not read Username` | Private repository: create a GitHub fine-grained token (read-only Contents), add it as credential `github-token`, and add `credentials('github-token')` inside `remote { }` in `helm/values.yaml` |
| PVC `Pending` for a long time | EBS CSI controller not running: `kubectl -n kube-system get pods -l app=ebs-csi-controller` |

---

## History

Until 2026-10-26, Jenkins ran in Docker Desktop on a laptop (`jenkins/docker-compose.yml`) and deployed with
the access key of IAM user `jenkins-ci`. That setup, the user, its key and its EKS access entry have been
removed; the old files are in the Git history before the commit "Remove laptop Jenkins".
