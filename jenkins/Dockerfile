# Jenkins with the tools the account-service pipeline needs:
#   docker CLI (talks to Docker Desktop through the mounted socket), AWS CLI v2, kubectl, git
# Built and started by jenkins/docker-compose.yml.
FROM jenkins/jenkins:lts-jdk21

USER root

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl unzip git ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# Docker CLI + buildx plugin (the Docker engine itself is Docker Desktop on your laptop)
COPY --from=docker:28-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker:28-cli /usr/local/libexec/docker/cli-plugins/docker-buildx /usr/local/libexec/docker/cli-plugins/docker-buildx

# AWS CLI v2
RUN curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o /tmp/awscliv2.zip \
 && unzip -q /tmp/awscliv2.zip -d /tmp \
 && /tmp/aws/install \
 && rm -rf /tmp/aws /tmp/awscliv2.zip

# kubectl (latest stable release)
RUN curl -fsSLo /usr/local/bin/kubectl \
      "https://dl.k8s.io/release/$(curl -fsSL https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl" \
 && chmod +x /usr/local/bin/kubectl

# Jenkins plugins the pipeline uses
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt
