#!/bin/bash

echo "Usage: start.sh [<moqui directory like . >]"; echo

MOQUI_HOME="${1:-.}"

GRADLE_COMMAND=${GRADLE_COMMAND:=""}
GRADLE_ARGS=${GRADLE_ARGS:="--info --no-daemon --parallel"}

COMPONENT=${COMPONENT:=""}
COMPONENT_SET=${COMPONENT_SET:=""}

RUN_LOCAL_SEARCH=${RUN_LOCAL_SEARCH:="true"}
search_name=${search_name:="opensearch"}

# Check if SDKMAN! is installed, if not, install it
if [ ! -d "$HOME/.sdkman" ]; then
  echo "SDKMAN! not found. Installing SDKMAN!..."
  curl -s "https://get.sdkman.io" | bash
  source "$HOME/.sdkman/bin/sdkman-init.sh"
fi

# Load SDKMAN!
source "$HOME/.sdkman/bin/sdkman-init.sh"

# Check if Gradle 7.4.1 is installed, if not, install it
if ! sdk list gradle | grep -q "7.4.1"; then
  echo "Gradle 7.4.1 not found. Installing Gradle 7.4.1..."
  sdk install gradle 7.4.1
fi

# Check if Java 11 Temurin is installed, if not, install it
if ! sdk list java | grep -q "11-tem"; then
  echo "Java 11 Temurin not found. Installing Java 11 Temurin..."
  sdk install java 11.0.17-tem
fi

# Set Java 11 Temurin as default
sdk use java 11.0.17-tem

echo "Gradle and Java setup complete."

# Rest of your script
if [ -f $MOQUI_HOME/moqui-plus-runtime.war ]; then
  echo "Using already built moqui-plus-runtime.war"
else
  echo "cd into the $MOQUI_HOME directory"; START_PATH=$(pwd); cd $MOQUI_HOME

  if [ ! -d $MOQUI_HOME/runtime ]; then echo "Getting runtime"; gradle $GRADLE_ARGS getRuntime; fi
  if [ "$RUN_LOCAL_SEARCH" == "true" ]; then
    if [ "$search_name" != "elasticsearch" ]; then \
      if [ ! -d $MOQUI_HOME/runtime/opensearch/bin ]; then echo "Installing OpenSearch"; gradle $GRADLE_ARGS downloadOpenSearch; fi
    elif [ -d runtime/elasticsearch/bin ]; then \
      if [ ! -d $MOQUI_HOME/runtime/elasticsearch/bin ]; then echo "Installing ElasticSearch"; gradle $GRADLE_ARGS downloadElasticSearch; fi
    fi;
  fi

  if [ -n "$GRADLE_COMMAND" ]; then echo "Running gradle $GRADLE_ARGS $GRADLE_COMMAND"; gradle $GRADLE_ARGS "$GRADLE_COMMAND"; fi

  if [ $USE_HAZELCAST == "true" ]; then echo "Getting moqui-hazelcast"; gradle $GRADLE_ARGS getComponent -Pcomponent=moqui-hazelcast; fi
  if [ -n "$COMPONENT" ]; then echo "Getting $COMPONENT"; gradle $GRADLE_ARGS getComponent -Pcomponent=$COMPONENT; fi
  if [ -n "$COMPONENT_SET" ]; then echo "Getting $COMPONENT_SET"; gradle $GRADLE_ARGS getComponentSet -PcomponentSet=$COMPONENT_SET; fi

  echo "Getting Dependencies"; gradle $GRADLE_ARGS getDepends
  echo "Add runtime"; gradle $GRADLE_ARGS addRuntime
  echo "Done"; cd $START_PATH
fi
