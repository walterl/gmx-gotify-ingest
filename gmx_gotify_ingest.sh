#!/usr/bin/env bash

set -eu

GGI_DIR=$(dirname $0)

command -v bb > /dev/null || (echo "ERROR: Babashka command (bb) not found!" && exit 1)
source ~/.config/gmx-gotify-ingest/env.sh
bb --debug "$GGI_DIR/gmx_gotify_ingest.clj" run 2>&1 | logger -t gotify_ingest
