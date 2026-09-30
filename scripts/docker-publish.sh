#!/usr/bin/env bash
# Achernar Observer — multi-architecture Docker publish.
#
# Builds and pushes frontend + backend images for:
#   linux/amd64, linux/arm64
# as a single multi-platform manifest per tag (no per-arch tags).
#
# Usage:
#   APP_VERSION=2.2.0 ./scripts/docker-publish.sh
#   ./scripts/docker-publish.sh 2.2.0
#   ./scripts/docker-publish.sh 2.2.0 latest   # also move :latest (default)
#   ./scripts/docker-publish.sh 2.2.0 ''       # publish only the version tag
#
# Requirements: Docker with buildx + login (docker login).
# The script is idempotent: re-running it reuses the builder if it exists.
set -euo pipefail

VERSION="${1:-${APP_VERSION:-2.2.0}}"
EXTRA_TAG="${2:-${EXTRA_TAG:-latest}}"
PLATFORMS="${PLATFORMS:-linux/amd64,linux/arm64}"
BUILDER_NAME="${BUILDER_NAME:-achernar-builder}"
FRONTEND_REPO="${FRONTEND_REPO:-eridani/achernar-observer-frontend}"
BACKEND_REPO="${BACKEND_REPO:-eridani/achernar-observer-backend}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

if ! docker buildx version >/dev/null 2>&1; then
  echo "ERROR: docker buildx is required." >&2
  exit 1
fi

# Idempotent builder setup (docker-container driver supports multi-platform push).
if docker buildx inspect "${BUILDER_NAME}" >/dev/null 2>&1; then
  echo "Using existing buildx builder '${BUILDER_NAME}'."
else
  echo "Creating buildx builder '${BUILDER_NAME}'."
  docker buildx create \
    --name "${BUILDER_NAME}" \
    --driver docker-container \
    --use
fi
docker buildx use "${BUILDER_NAME}"
docker buildx inspect --bootstrap

build_one() {
  local context_dir="$1" repo="$2"
  local tags=(-t "${repo}:${VERSION}")
  if [ -n "${EXTRA_TAG}" ]; then
    tags+=(-t "${repo}:${EXTRA_TAG}")
  fi
  echo "==> Building ${repo}:${VERSION} for ${PLATFORMS} (context: ${context_dir})"
  # NOTE: --push is required for multi-platform; --load cannot hold >1 platform.
  docker buildx build \
    --builder "${BUILDER_NAME}" \
    --platform "${PLATFORMS}" \
    --build-arg "APP_VERSION=${VERSION}" \
    "${tags[@]}" \
    --push \
    "${context_dir}"
}

build_one "${ROOT_DIR}/backend" "${BACKEND_REPO}"
build_one "${ROOT_DIR}/frontend" "${FRONTEND_REPO}"

echo "==> Verifying published manifests"
docker buildx imagetools inspect "${BACKEND_REPO}:${VERSION}"
docker buildx imagetools inspect "${FRONTEND_REPO}:${VERSION}"

cat <<EOF
Done. Published:
  ${BACKEND_REPO}:${VERSION}$( [ -n "${EXTRA_TAG}" ] && echo " + :${EXTRA_TAG}" )
  ${FRONTEND_REPO}:${VERSION}$( [ -n "${EXTRA_TAG}" ] && echo " + :${EXTRA_TAG}" )
Platforms: ${PLATFORMS}
Run anywhere (Linux AMD64/ARM64, macOS Intel/Apple Silicon, Windows WSL2 Linux Containers):
  BACKEND_IMAGE=${BACKEND_REPO}:${VERSION} FRONTEND_IMAGE=${FRONTEND_REPO}:${VERSION} docker compose up -d
EOF
