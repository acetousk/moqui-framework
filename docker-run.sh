#!/bin/bash

echo "Usage: docker-run.sh"; echo

./gradlew gitPullAll
./gradlew addRuntime
cd docker
sudo ./build-compose-up.sh prod.yml
sudo docker compose logs -f
trap 'cd ..' EXIT
