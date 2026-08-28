#!/usr/bin/env bash
podman ps -a --filter name=tellij- --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}'
