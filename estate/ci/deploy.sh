#!/usr/bin/env bash
# Deploys a task definition to an ECS service through CodeDeploy (AppSpec content) and waits for the deployment to end.
#   deploy.sh <application> <deployment-group> <task-definition-arn> <container> <port>
# Exit 0 when the deployment Succeeded; its error information and exit 1 otherwise (or after $DEPLOY_TIMEOUT seconds, default 180).
set -euo pipefail
app=$1 group=$2 taskdef=$3 container=$4 port=$5
ep=(); [ -n "${AWS_ENDPOINT_URL:-}" ] && ep=(--endpoint-url "$AWS_ENDPOINT_URL")
spec=$(jq -cn --arg td "$taskdef" --arg c "$container" --argjson p "$port" \
  '{version: "0.0", Resources: [{TargetService: {Type: "AWS::ECS::Service", Properties: {TaskDefinition: $td, LoadBalancerInfo: {ContainerName: $c, ContainerPort: $p}}}}]}')
revision=$(jq -cn --arg s "$spec" '{revisionType: "AppSpecContent", appSpecContent: {content: $s}}')
id=$(aws "${ep[@]}" deploy create-deployment --application-name "$app" --deployment-group-name "$group" --revision "$revision" --query deploymentId --output text)
echo "deployment $id of $taskdef"
end=$((SECONDS + ${DEPLOY_TIMEOUT:-180}))
while [ $SECONDS -lt $end ]; do
  info=$(aws "${ep[@]}" deploy get-deployment --deployment-id "$id" --query deploymentInfo --output json)
  status=$(jq -r .status <<<"$info")
  echo "deployment $id: $status"
  case $status in
    Succeeded) exit 0 ;;
    Failed|Stopped) jq -c '.errorInformation // {}' <<<"$info" >&2; exit 1 ;;
  esac
  sleep 2
done
echo "deployment $id did not finish in time" >&2
exit 1
