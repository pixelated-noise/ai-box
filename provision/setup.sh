#!/bin/sh
# This runs as root inside the Alpine VM after networking and SSH are configured.
# Edit this file to add packages and configuration.

apk update
apk add curl
