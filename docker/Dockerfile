# Demo Jenkins with the failure-explainer library pre-installed.
# Build from the repo root:  docker compose up --build
FROM jenkins/jenkins:lts-jdk21

USER root

# Recorded failure logs and the script that replays them inside a real sh step
COPY demo/logs /opt/demo/logs
COPY docker/replay.sh /opt/demo/replay.sh

# Snapshot of the shared library as a local Git repo, so Jenkins loads it exactly like a
# library hosted on GitHub. Rebuild the image (docker compose up --build) after changing it.
COPY src /opt/explainer-lib/src
COPY vars /opt/explainer-lib/vars
RUN chmod +x /opt/demo/replay.sh \
 && cd /opt/explainer-lib \
 && git init -q -b main \
 && git add . \
 && git -c user.name=demo -c user.email=demo@localhost commit -q -m "library snapshot" \
 && chown -R jenkins:jenkins /opt/explainer-lib

USER jenkins

COPY --chown=jenkins:jenkins docker/plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

COPY --chown=jenkins:jenkins docker/casc /opt/casc

ENV CASC_JENKINS_CONFIG=/opt/casc/jenkins.yaml
# No setup wizard; allow the library to be cloned from the local file:// repo above.
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false -Dhudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true"
