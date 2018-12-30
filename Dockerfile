FROM cypress/base:10

# to make sure that the cache is only used during one day, run docker build --build-arg CACHEBUST=$(date "+%Y-%m-%d")
# that way we should get the latest updates since the release of our base image 
# thx to https://github.com/moby/moby/issues/1996#issuecomment-185872769
ARG CACHEBUST=1

ARG GIT_BRANCH=master

RUN apt-get update && apt-get -y upgrade && apt-get -y autoremove

# shallow-clone the $GIT_BRANCH branch
RUN git clone -b $GIT_BRANCH --depth 1 https://github.com/metasfresh/metasfresh-webui-frontend.git e2e

WORKDIR /e2e

# COPY package.json /e2e/package.json

RUN npm install --save-dev cypress@3.1.4
RUN npm install --save-dev @cypress/snapshot
RUN npm install --save-dev @cypress/webpack-preprocessor

# COPY index.html /e2e/index.html
# COPY cypress.json /e2e/cypress.json
# COPY webpack.config.js /e2e/webpack.config.js
# COPY src /e2e/src
# COPY cypress /e2e/cypress


# the following npm install is needed; without it, running cypress will fail like this:
# Your pluginsFile is set to '/metasfresh-webui-frontend/cypress/plugins/index.js', but either the file is missing, it contains a syntax error, or threw an error when required. The pluginsFile must be a .js or .coffee file.

# Please fix this, or set 'pluginsFile' to 'false' if a plugins file is not necessary for your project.

# The following error was thrown:

# Error: Cannot find module 'webpack'
#     at Function.Module._resolveFilename (module.js:485:15)
RUN npm install

RUN $(npm bin)/cypress verify

#add entry-script
COPY run_cypress.sh /
# owner may read and execute
RUN chmod 0500 /run_cypress.sh

ENTRYPOINT ["/run_cypress.sh"]
