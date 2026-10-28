# Google Home APIs SDK

Google hands the Home APIs out as a bundle, not a public Maven artifact
(Google's Maven has only the unrelated Matter commissioning SDK under the
same name). Download the Android SDK bundle from
https://developers.home.google.com/apis/android/sdk after signing in to
Google Home Developers, and unpack its Maven repository here as `m2/`, so
that `m2/com/google/android/gms/play-services-home/<version>/` exists.
Loose `.aar` files dropped straight into this directory work as well.

The SDK being here is the decision: a build that finds it links it and
compiles the real `GoogleHomeSupport` (`feature/ime/src/googlehome`). It adds
about 22 MB of dex before R8 to a build that carries it whole; the Play build
gets it as an on-demand split instead. To build without it, move the `m2`
directory aside.

Only a signing certificate with an Android OAuth client in the Cloud
project can complete Google's consent. Without the SDK, the build uses the
stub, and the smart home tool never offers a Google Home row.

Nothing under this directory but this file is committed. The F-Droid recipe
deletes the directory before building.
