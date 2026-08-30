#!/usr/bin/env bash
podman ps -a --filter name=tellij- --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}'
printf 'demo-api         %s\n' "$(curl -sf -o /dev/null -w 'Up (HTTP %{http_code} on :8080)' http://localhost:8080/v3/api-docs 2>/dev/null || echo down)"
