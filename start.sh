#! /bin/bash

echo "Usage: start.sh [<moqui directory like . >]"; echo

MOQUI_HOME="${1:-.}"

GRADLE_COMMAND=${GRADLE_COMMAND:=""}
GRADLE_ARGS=${GRADLE_ARGS:="--info --no-daemon --parallel"}

USE_HAZELCAST=${USE_HAZELCAST:="false"};
get_hazelcast () { if [ $USE_HAZELCAST = "true" ]; then echo "Getting moqui-hazelcast"; ./gradlew $GRADLE_ARGS getComponent -Pcomponent=moqui-hazelcast; fi  }

if [ -f $MOQUI_HOME/moqui-plus-runtime.war ]; then get_hazelcast; echo "Using already built moqui-plus-runtime.war"
else
  echo "cd into the $MOQUI_HOME directory"; START_PATH=$(pwd); cd $MOQUI_HOME

  if [ ! -d $MOQUI_HOME/runtime ]; then echo "Getting runtime"; ./gradlew $GRADLE_ARGS getRuntime; fi
  if [ ! -d $MOQUI_HOME/runtime/opensearch/bin ]; then echo "Installing OpenSearch"; ./gradlew $GRADLE_ARGS downloadOpenSearch; fi

  if [ -n "$GRADLE_COMMAND" ]; then echo "Running gradle $GRADLE_ARGS $GRADLE_COMMAND"; ./gradlew $GRADLE_ARGS "$GRADLE_COMMAND"; fi

  get_hazelcast

  echo "Getting Dependencies"; ./gradlew $GRADLE_ARGS getDepends
  echo "Add runtime"; ./gradlew $GRADLE_ARGS addRuntime
  echo "Done"; cd $START_PATH
fi
