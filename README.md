# sbt-trickle

Trickles down updated dependencies across repositories through pull requests,
minimizing builds and maximizing parallelism.

It requires that all projects be running this plugin, and a location where the
global state can be recorded. A git connector is provided to record that state
on a remote git repository.

Published for SBT 1.3.8.

## Configuration

On `project/plugins.sbt`:

```sbt
resolvers += Resolver.bintrayRepo("dcsobral", "maven")

addSbtPlugin("com.dcsobral" % "sbt-trickle" % <version>)
```

On your `build.sbt` settings:

```sbt
// Information about your project repository
trickleRepositoryName in ThisBuild := "<unique name>", // defaults to current directory
trickleRepositoryURI in ThisBuild := "<url>",          // used to automatically submit PRs

// Information about the metadata central repository
trickleDbURI in ThisBuild := "<url>",                  // Used to pull and push dependency information
```

## Usage

The following tasks as provided:

```sbt
trickleUpdateSelf  // Update metadata repository with this project's information
```

## Troubleshooting

### Authentication Issues

#### SSH with Public/Private Key Authentication

```
org.eclipse.jgit.api.errors.TransportException: <repo>: Auth fail
org.eclipse.jgit.api.errors.TransportException: <repo>: invalid privatekey: [B@26619706
```

The private key might be in a format not supported by jsch, the library used by sbt-trickle. From
[Stack Overflow](https://stackoverflow.com/a/55740276/53013):

> Recent versions of OpenSSH (7.8 and newer) generate keys in new OpenSSH format by default,
> which start with:

```
-----BEGIN OPENSSH PRIVATE KEY-----
```

> JSch does not support this key format.

> You can use ssh-keygen to convert the key to the classic OpenSSH format:

```
ssh-keygen -p -f file -m pem
```

## Trivia

The name was inspired by the Mammoth Terraces on Yellowstone National Park, but
sbt-terraces just didn't evoke the right image.

