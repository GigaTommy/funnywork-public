#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
DEVICE=0
TEAM=${FUNNYWORK_IOS_TEAM_ID:-}
TEAM_OPTION=0
PROFILE=${FUNNYWORK_IOS_PROFILE_NAME:-}
PROFILE_OPTION=0
PROVISION=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --device) DEVICE=1; shift ;;
    --team)
      [ "$#" -ge 2 ] || { echo '--team requires an Apple development team ID.' >&2; exit 2; }
      TEAM=$2; TEAM_OPTION=1; shift 2 ;;
    --allow-provisioning-updates) PROVISION=1; shift ;;
    --profile)
      [ "$#" -ge 2 ] && [ -n "$2" ] || { echo '--profile requires an installed provisioning profile name.' >&2; exit 2; }
      PROFILE=$2; PROFILE_OPTION=1; shift 2 ;;
    --help)
      echo 'Usage: build-ios.sh [--device --team TEAM_ID [--profile NAME | --allow-provisioning-updates]] [xcodebuild arguments]'
      echo 'Defaults to an unsigned simulator build. Device builds retain Push Notifications.'
      echo 'Provisioning updates may register the app and generate profiles in your Apple developer account.'
      echo 'FUNNYWORK_IOS_TEAM_ID may supply the device signing team. Custom options precede xcodebuild arguments.'
      echo 'FUNNYWORK_IOS_PROFILE_NAME may select an installed profile for the application target only.'
      exit 0 ;;
    --) shift; break ;;
    *) break ;;
  esac
done
if [ "$DEVICE" -eq 1 ]; then
  case "$TEAM" in
    ''|*[!A-Z0-9]*) echo 'Provide a valid 10-character Apple development team ID with --team.' >&2; exit 2 ;;
  esac
  [ "${#TEAM}" -eq 10 ] || { echo 'Apple development team IDs must contain 10 characters.' >&2; exit 2; }
  if [ -n "$PROFILE" ] && [ "$PROVISION" -eq 1 ]; then
    echo '--profile cannot be combined with --allow-provisioning-updates.' >&2; exit 2
  fi
elif [ "$PROVISION" -eq 1 ] || [ "$TEAM_OPTION" -eq 1 ] || [ "$PROFILE_OPTION" -eq 1 ]; then
  echo '--team, --profile and --allow-provisioning-updates require --device.' >&2; exit 2
fi
command -v xcodegen >/dev/null 2>&1 || { echo 'Install XcodeGen before building iOS.' >&2; exit 1; }
xcodegen generate --spec "$ROOT/apps/ios/project.yml"
if [ "$DEVICE" -eq 1 ]; then
  SIGNING_STYLE=Automatic
  if [ -n "$PROFILE" ]; then SIGNING_STYLE=Manual; fi
  if [ "$PROVISION" -eq 1 ]; then set -- -allowProvisioningUpdates "$@"; fi
  exec xcodebuild -project "$ROOT/apps/ios/FunnyWork.xcodeproj" -scheme FunnyWork \
    -sdk iphoneos -destination 'generic/platform=iOS' -derivedDataPath "$ROOT/.local/ios-device-build" \
    CODE_SIGNING_ALLOWED=YES "FUNNYWORK_IOS_SIGNING_STYLE=$SIGNING_STYLE" \
    "DEVELOPMENT_TEAM=$TEAM" "FUNNYWORK_IOS_TEAM_ID=$TEAM" "FUNNYWORK_IOS_PROFILE_NAME=$PROFILE" \
    'CODE_SIGN_IDENTITY=Apple Development' APS_ENVIRONMENT=development "$@" build
fi
exec xcodebuild -project "$ROOT/apps/ios/FunnyWork.xcodeproj" -scheme FunnyWork -sdk iphonesimulator -derivedDataPath "$ROOT/.local/ios-build" CODE_SIGNING_ALLOWED=NO "$@" build
