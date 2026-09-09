#!/bin/bash
set -euo pipefail

repo_root="${1:-$(pwd)}"

git -C "$repo_root/frameworks/base" apply \
  "$repo_root/vendor/asiks/camper-navigator-service/patches/frameworks_base/0001-start-camper-navigator-system-service.patch"

git -C "$repo_root/frameworks/base" apply \
  "$repo_root/vendor/asiks/camper-navigator-service/patches/frameworks_base/0002-include-camper-navigator-in-services-core.patch"

echo "Framework patches applied."
echo "Add the service_contexts entry to your system/sepolicy/private/service_contexts if it is not imported by your product."
