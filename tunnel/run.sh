#!/usr/bin/env sh
set -e
cd "$(dirname "$0")"
if [ ! -f .venv/bin/python ]; then
  echo "Creating virtual environment in tunnel/.venv ..."
  python3 -m venv .venv
  .venv/bin/pip install -r requirements.txt
fi
exec .venv/bin/python server.py "$@"
