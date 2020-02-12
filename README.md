# sbt-trickle

Trickles down updated dependencies across repositories through pull requests,
minimizing builds and maximizing parallelism.

It requires that all projects be running this plugin, and a location where the
global state can be recorded. A git connector is provided to record that state
on a remote git repository.

## Usage

On `project/plugins.sbt`:

```sbt
resolvers += Resolver.bintrayRepo("dcsobral", "maven")

addSbtPlugin("com.slamdata" % "sbt-trickle" % <version>)
```

On your `build.sbt`:


```sbt
// Configure shared repository
// Configure dependency update method
// Configure PR creation
```

Published for SBT 1.3.8.

## Trivia

The name was inspired by the Mammoth Terraces on Yellowstone National Park, but
sbt-terraces just didn't evoke the right image.

