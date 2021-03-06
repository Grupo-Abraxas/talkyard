# Node.js 10.x Linux Dockerfile, for running Gulp & Tape, for Talkyard.
#
# Unfortunately, the default Node.js Dockerfile creates a user 'node' with id 1000.
# However, most people on Linux have id 1000 already, so 'node' = 1000 results in an error
# in the entrypoint when it creates and su:s to a user with the same id as the host user [5RZ4HA9].
# As a workaround, I've copied node:8.1.0 to here, and commented out the creation of user 1000:
# (search for "1000" to find where)
#-----------------------------------------------------------------------------
# This, between ----, is a copy of:
#   https://github.com/nodejs/docker-node/blob/master/10/alpine/Dockerfile
#
# Copyright (c) 2015 Joyent, Inc.
# Copyright (c) 2015 Node.js contributors
# The MIT License (MIT)
# (see https://github.com/nodejs/docker-node/blob/master/LICENSE )

FROM alpine:3.7

ENV NODE_VERSION 10.5.0

# Don't: (see comment above)
#RUN addgroup -g 1000 node \
#    && adduser -u 1000 -G node -s /bin/sh -D node \

RUN  apk add --no-cache \
        libstdc++ \
    # No, let's keep Python etc — needed later below, for node-gyp (KEEPDEPS)
    # && apk add --no-cache --virtual .build-deps \
    && apk add --no-cache \
        binutils-gold \
        curl \
        g++ \
        gcc \
        gnupg \
        libgcc \
        linux-headers \
        make \
        python \
  # gpg keys listed at https://github.com/nodejs/node#release-team
  && for key in \
    94AE36675C464D64BAFA68DD7434390BDBE9B9C5 \
    FD3A5288F042B6850C66B31F09FE44734EB7990E \
    71DCFD284A79C3B38668286BC97EC7A07EDE3FC1 \
    DD8F2338BAE7501E3DD5AC78C273792F7D83545D \
    C4F0DFFF4E8C1A8236409D08E73BC641CC11F4C8 \
    B9AE9905FFD7803F25714661B63B535A4C206CA9 \
    56730D5401028683275BD23C23EFEFE93C4CFFFE \
    77984A986EBC2AA786BC0F66B01FBB92821C587A \
    8FCCA13FEF1D0C2E91008E09770F7A9A5AE15600 \
  ; do \
    gpg --keyserver hkp://p80.pool.sks-keyservers.net:80 --recv-keys "$key" || \
    gpg --keyserver hkp://ipv4.pool.sks-keyservers.net --recv-keys "$key" || \
    gpg --keyserver hkp://pgp.mit.edu:80 --recv-keys "$key" ; \
  done \
    && curl -fsSLO --compressed "https://nodejs.org/dist/v$NODE_VERSION/node-v$NODE_VERSION.tar.xz" \
    && curl -fsSLO --compressed "https://nodejs.org/dist/v$NODE_VERSION/SHASUMS256.txt.asc" \
    && gpg --batch --decrypt --output SHASUMS256.txt SHASUMS256.txt.asc \
    && grep " node-v$NODE_VERSION.tar.xz\$" SHASUMS256.txt | sha256sum -c - \
    && tar -xf "node-v$NODE_VERSION.tar.xz" \
    && cd "node-v$NODE_VERSION" \
    && ./configure \
    && make -j$(getconf _NPROCESSORS_ONLN) \
    && make install \
    # && apk del .build-deps \  # no, keep Python etc (KEEPDEPS)
    && cd .. \
    && rm -Rf "node-v$NODE_VERSION" \
    && rm "node-v$NODE_VERSION.tar.xz" SHASUMS256.txt.asc SHASUMS256.txt

ENV YARN_VERSION 1.7.0

#RUN apk add --no-cache --virtual .build-deps-yarn curl gnupg tar \  # (KEEPDEPS). curl = can debug, nice
RUN apk add --no-cache curl gnupg tar \
  && for key in \
    6A010C5166006599AA17F08146C2130DFD2497F5 \
  ; do \
    gpg --keyserver hkp://p80.pool.sks-keyservers.net:80 --recv-keys "$key" || \
    gpg --keyserver hkp://ipv4.pool.sks-keyservers.net --recv-keys "$key" || \
    gpg --keyserver hkp://pgp.mit.edu:80 --recv-keys "$key" ; \
  done \
  && curl -fsSLO --compressed "https://yarnpkg.com/downloads/$YARN_VERSION/yarn-v$YARN_VERSION.tar.gz" \
  && curl -fsSLO --compressed "https://yarnpkg.com/downloads/$YARN_VERSION/yarn-v$YARN_VERSION.tar.gz.asc" \
  && gpg --batch --verify yarn-v$YARN_VERSION.tar.gz.asc yarn-v$YARN_VERSION.tar.gz \
  && mkdir -p /opt \
  && tar -xzf yarn-v$YARN_VERSION.tar.gz -C /opt/ \
  && ln -s /opt/yarn-v$YARN_VERSION/bin/yarn /usr/local/bin/yarn \
  && ln -s /opt/yarn-v$YARN_VERSION/bin/yarnpkg /usr/local/bin/yarnpkg \
  && rm yarn-v$YARN_VERSION.tar.gz.asc yarn-v$YARN_VERSION.tar.gz
  # && apk del .build-deps-yarn  # no, don't (KEEPDEPS)
#-----------------------------------------------------------------------------


# Git is needed by gulpfile.js. The others are nice for troubleshooting, e.g. Tape security tests
# that send http requests — then curl is nice to have, so can replay the requests manually in Bash.
RUN apk add --no-cache bash tree curl net-tools git

# If this error happens:
# gulp[33]: ../src/node_contextify.cc:629:static void node::contextify::ContextifyScript::New(const v8::FunctionCallbackInfo<v8::Value>&): Assertion `args[1]->IsString()' failed.
# Then upgrade Gulp to a more recent version: yarn upgarde gulp
# Maybe delete node_modules, and docker/gulp-home
# More here: https://github.com/gulpjs/gulp/issues/2162  (happened for me with Ubuntu Linux)


WORKDIR /opt/talkyard/server/

RUN yarn global add gulp \
 && yarn global add gulp-cli

COPY entrypoint.sh /docker-entrypoint.sh
RUN  chmod ugo+x   /docker-entrypoint.sh
ENTRYPOINT ["/docker-entrypoint.sh"]

# For debugging test code, via `node --debug-brk --inspect=9229`. [8EA02R4]
EXPOSE 9229


CMD ["echo 'Specify a command in docker-compose.yml or on the command line instead. Bye.' && exit 0"]

