#!/bin/bash

set -e -x

pushd plugins/backend-auth-adapter
./gradlew build
popd

pushd plugins/backend-chat-adapter
./gradlew build
popd

pushd plugins/backend-connection-announce
./gradlew build
popd

pushd plugins/backend-global-teleporter
./gradlew build
popd

pushd plugins/backend-location-resolver
./gradlew build
popd

pushd plugins/backend-tp-command
./gradlew build
popd

pushd plugins/velocity-auth-adapter
./gradlew build
popd

pushd plugins/velocity-chat-control
./gradlew build
popd

pushd plugins/velocity-global-teleporter
./gradlew build
popd

pushd plugins/velocity-last-server
./gradlew build
popd

pushd plugins/velocity-tablist-broadcast
./gradlew build
popd