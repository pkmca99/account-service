# Jenkins CI/CD for account-service

Every push to GitHub runs this pipeline (defined in the `Jenkinsfile` at the project root):

```
GitHub ──► Checkout ──► Build & unit tests ──► Docker image ──► Push to ECR ──► Deploy to EKS ──► Smoke test
           (git)        (mvn verify,           (Dockerfile)     (tag: build-     (kubectl apply,   (curl health +
                         JUnit report)                           commit)          rollout status)    accounts API)
                                                                                        │                  │
                                                                                        └── fails? ──► automatic rollback
```

Jenkins runs in Docker on your laptop. It checks GitHub for new commits every 5 minutes; a laptop can't
receive GitHub webhooks without a public URL.

All commands below are for **Windows CMD** in `C:\Users\pkmca\Downloads\account-service`
unless a step says otherwise.

---

## Step 0. What you need

| Tool | Check with |
|---|---|
| Docker Desktop, running | `docker version` |
| Git | `git --version` |
| AWS CLI, signed in as you | `aws sts get-caller-identity` |
| A GitHub account | |

## Step 1. Put the project on GitHub

Skip this if the code is already on GitHub with the new `Jenkinsfile`; just commit and push the new files.

1. On github.com create an **empty** repository named `account-service` (no README, no .gitignore).
2. Push the project:

```
cd C:\Users\pkmca\Downloads\account-service
git init -b main
git add .
git status
git commit -m "account-service with logging and Jenkins pipeline"
git remote add origin https://github.com/<your-github-user>/account-service.git
git push -u origin main
```

Check `git status` before committing: **`eksctl.exe` must not be in the list.** It is 150 MB and GitHub
rejects files over 100 MB. The `.gitignore` file already excludes it, along with `target/` and `.idea/`.

## Step 2. Create an AWS user for Jenkins

Jenkins needs its own access key; your `aws login` session expires and can't be used by Jenkins.
Run these signed in as yourself (the user who created the cluster).

```
:: 1. The user
aws iam create-user --user-name jenkins-ci

:: 2. Push images to ECR
aws iam attach-user-policy --user-name jenkins-ci --policy-arn arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryPowerUser

:: 3. Read the cluster details (needed for "aws eks update-kubeconfig")
aws iam put-user-policy --user-name jenkins-ci --policy-name eks-describe --policy-document file://jenkins/iam/eks-describe-policy.json

:: 4. Access key: copy AccessKeyId and SecretAccessKey now, the secret is shown only once
aws iam create-access-key --user-name jenkins-ci
```

Then allow `jenkins-ci` to deploy inside the cluster (EKS access entry):

```
aws eks create-access-entry --cluster-name accountsevice-cluster --region us-east-1 --principal-arn arn:aws:iam::546850508021:user/jenkins-ci

aws eks associate-access-policy --cluster-name accountsevice-cluster --region us-east-1 --principal-arn arn:aws:iam::546850508021:user/jenkins-ci --policy-arn arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy --access-scope type=cluster
```

If `create-access-entry` says the cluster's authentication mode doesn't support access entries, turn them on
and run it again (takes a minute or two):

```
aws eks update-cluster-config --name accountsevice-cluster --region us-east-1 --access-config authenticationMode=API_AND_CONFIG_MAP
```

Cluster admin is the simple choice for a practice cluster. For a real team, give the CI user a
narrower policy.

## Step 3. Start Jenkins

```
cd C:\Users\pkmca\Downloads\account-service\jenkins
docker compose up -d --build
```

The first build takes about 5 minutes (it installs the Docker CLI, AWS CLI, kubectl and plugins).
When it's done:

1. Get the first-login password:
   ```
   docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword
   ```
2. Open **http://localhost:8090** and paste the password.
3. Choose **Install suggested plugins**.
4. Create your admin user, keep the Jenkins URL as `http://localhost:8090/`, and click **Start using Jenkins**.

Check the tools inside the container:

```
docker exec jenkins docker version
docker exec jenkins aws --version
docker exec jenkins kubectl version --client
```

`docker version` must show both **Client** and **Server**. If Server is missing, Docker Desktop isn't
running or the socket isn't mounted.

## Step 4. Add credentials in Jenkins

Go to **Manage Jenkins → Credentials → System → Global credentials (unrestricted) → Add Credentials**.

| Kind | ID (type exactly) | Username | Password |
|---|---|---|---|
| Username with password | `aws-jenkins` | AccessKeyId from Step 2 | SecretAccessKey from Step 2 |
| Username with password | `github-token` | your GitHub username | a GitHub personal access token |

The `github-token` is only needed for a **private** repository. Create the token on GitHub under
**Settings → Developer settings → Personal access tokens → Fine-grained tokens**, giving it read-only
**Contents** access to the `account-service` repository.

## Step 5. Create the pipeline job

1. Jenkins home → **New Item** → name `account-service` → **Pipeline** → **OK**.
2. Under **Pipeline**:
   - **Definition:** Pipeline script from SCM
   - **SCM:** Git
   - **Repository URL:** `https://github.com/<your-github-user>/account-service.git`
   - **Credentials:** `github-token` (private repo only)
   - **Branch Specifier:** `*/main`
   - **Script Path:** `Jenkinsfile`
3. **Save**.

## Step 6. Run it

1. Click **Build Now**. The first run reads the `Jenkinsfile` and turns on the `DEPLOY` parameter and the
   5-minute GitHub check. After that the button is **Build with Parameters**.
2. Watch the stages on the job page, or open the build → **Console Output**.
3. The first build is slow because Maven downloads every dependency. They're cached afterwards in the
   `maven-repo` Docker volume.

What each stage does:

| Stage | What happens | Fails when |
|---|---|---|
| Checkout | Pulls the code; image tag = `<build number>-<commit>`, e.g. `7-a1b2c3d` | Repo URL or token wrong |
| Build & unit tests | `mvn clean verify` in a `maven:3.9-eclipse-temurin-25` container; JUnit results on the build page | A test fails or the code doesn't compile |
| Docker image | `docker build` with the project's Dockerfile | Dockerfile error |
| Push to ECR | Logs in to ECR and pushes `:<tag>` and `:latest` | `aws-jenkins` key wrong or missing ECR permission |
| Deploy to EKS | `kubectl apply` of `k8s.yml` with this build's tag, then waits for the rollout | Pod won't start (crash, probe failing, image pull error) → **rollback** |
| Smoke test | A temporary curl pod calls `/actuator/health/readiness` and `/api/v1/accounts` | Service unhealthy → **rollback** |

Untick `DEPLOY` in **Build with Parameters** to only build and test, without pushing or deploying.

## Step 7. Check the deployment

From your own terminal (after `aws login`):

```
kubectl -n accounts get deployment account-service -o jsonpath="{.spec.template.spec.containers[0].image}"
kubectl -n accounts rollout history deployment/account-service
kubectl -n accounts logs deployment/account-service --tail=20
```

The image should end with the tag shown in the Jenkins build description.

## Day-to-day

```
git add .
git commit -m "your change"
git push
```

Jenkins notices the commit within 5 minutes and runs the whole pipeline.

Roll back by hand at any time:

```
kubectl -n accounts rollout undo deployment/account-service
```

## Troubleshooting

| Error in Console Output | Fix |
|---|---|
| `Cannot connect to the Docker daemon` | Start Docker Desktop, then `docker compose restart` in the `jenkins` folder |
| `No such container: jenkins` | The Jenkins container must be named `jenkins` (set in `docker-compose.yml`) |
| `$'\r': command not found` | The Jenkinsfile was committed with Windows line endings. `.gitattributes` prevents this; run `git add --renormalize .`, commit and push |
| `could not find credentials entry with ID 'aws-jenkins'` | Credential ID typed differently in Step 4 |
| `AccessDenied ... ecr` or `eks:DescribeCluster` | IAM policies from Step 2 missing on `jenkins-ci` |
| `error: You must be logged in to the server (Unauthorized)` | EKS access entry from Step 2 missing |
| Deploy stage times out at `rollout status` | Check `kubectl -n accounts describe pod` events; on a Spot node the pod may be `Pending` |
| `Image line not found in k8s.yml` | The `image:` line in `k8s.yml` must be `546850508021.dkr.ecr.us-east-1.amazonaws.com/account-service:latest` |

## Stop, restart, remove

```
cd C:\Users\pkmca\Downloads\account-service\jenkins
docker compose stop          :: stop Jenkins, keep everything
docker compose start         :: start it again
docker compose down -v       :: remove Jenkins and ALL its jobs and settings
```

When you're done practicing, delete the access key (`aws iam delete-access-key --user-name jenkins-ci --access-key-id <id>`)
and the cluster, so nothing keeps costing money.
