#!/bin/sh
rm -rf .idea
./gradlew clean
rm -rf .gradle
rm -rf build
rm -rf */build
rm -rf ampere-ios/ampere-ios.xcworkspace
rm -rf ampere-ios/Pods
rm -rf ampere-ios/ampere-ios.xcodeproj/project.xcworkspace
rm -rf ampere-ios/ampere-ios.xcodeproj/xcuserdata 
