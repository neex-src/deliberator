#!/usr/bin/env bash
# Registers a new task definition revision of an ECS service: the family's latest revision with one container's image replaced.
#   register-taskdef.sh <cluster> <service> <container> <image>      -> prints the new task definition ARN
# Needs the aws CLI and jq; the endpoint, region and credentials come from the environment (AWS_ENDPOINT_URL, AWS_DEFAULT_REGION, ...).
set -euo pipefail
cluster=$1 service=$2 container=$3 image=$4
ep=(); [ -n "${AWS_ENDPOINT_URL:-}" ] && ep=(--endpoint-url "$AWS_ENDPOINT_URL")
current=$(aws "${ep[@]}" ecs describe-services --cluster "$cluster" --services "$service" --query 'services[0].taskDefinition' --output text)
[ -n "$current" ] && [ "$current" != None ] || { echo "no service $service in cluster $cluster" >&2; exit 1; }
family=${current##*/}; family=${family%%:*}
aws "${ep[@]}" ecs describe-task-definition --task-definition "$family" --query taskDefinition --output json \
  | jq --arg c "$container" --arg image "$image" \
       'del(.taskDefinitionArn, .revision, .status, .requiresAttributes, .compatibilities, .registeredAt, .registeredBy)
        | (.containerDefinitions[] | select(.name == $c) | .image) = $image' > taskdef.json
aws "${ep[@]}" ecs register-task-definition --cli-input-json file://taskdef.json --query taskDefinition.taskDefinitionArn --output text
