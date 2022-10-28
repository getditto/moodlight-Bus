# Moodlight Syncing Over Ditto Bus
Demo project to sync color changes over the Ditto Bus on IOS/Android

//Insert Video
//Add Youtube link

## Overview

There are two applications - one for iOS and one for Android - which display a colored screen. You can either select or randomize the color of the screen. When a color is selected the database changes will be synced to all other connected devices.

## Requirements

* iOS app requires iOS 14 as it is using a new SwiftUI Color Wheel component
* Android device must have Android 6 or higher

## Setup

1. Sign up for free and create a new app in the [Portal](https://portal.ditto.live/).

2. Each app created on the portal has a unique appID which can be seen on your app's settings page once the app has been created. Use this ID and replace "YOUR_APPID" in the app. Also, replace "YOUR_TOKEN" with the playground token that was generated for you app on the portal.

3. Run one or more instances of the application.
