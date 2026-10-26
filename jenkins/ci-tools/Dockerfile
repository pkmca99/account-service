# "tools" container of the Jenkins agent pod (jenkins/agent-pod.yaml): AWS CLI v2 + kubectl.
# kubectl stays within one minor version of the cluster (EKS 1.34).
#
#   aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin 546850508021.dkr.ecr.us-east-1.amazonaws.com
#   docker build -t 546850508021.dkr.ecr.us-east-1.amazonaws.com/ci-tools:1 jenkins/ci-tools
#   docker push 546850508021.dkr.ecr.us-east-1.amazonaws.com/ci-tools:1
FROM amazon/aws-cli:2.38.5

ARG KUBECTL_VERSION=v1.34.12
RUN curl -fsSLo /usr/local/bin/kubectl "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/amd64/kubectl" \
 && echo "$(curl -fsSL "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/amd64/kubectl.sha256")  /usr/local/bin/kubectl" | sha256sum -c - \
 && chmod +x /usr/local/bin/kubectl

# The base image's entrypoint is "aws"; the agent pod keeps this container idle and runs steps in it.
ENTRYPOINT []
CMD ["sleep", "infinity"]
