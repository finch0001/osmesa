#!/bin/bash

set -e

if [[ -n "${OSMESA_DEBUG}" ]]; then
    set -x
fi

function usage() {
    echo -n "Usage: $(basename "$0")

Build application for staging or a release.
"
}

if [[ -n "${GIT_COMMIT}" ]]; then
    GIT_COMMIT="${GIT_COMMIT:0:7}"
else
    GIT_COMMIT="$(git rev-parse --short HEAD)"
fi

if [ "${BASH_SOURCE[0]}" = "${0}" ]; then
    if [ "${1:-}" = "--help" ]; then
        usage
    else
        echo "This script is TODO"
    fi
fi
