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

// Optional settings
trickleDryMode := false,                               // If set to true, does not update remote
                                                       // or create pull requests
trickleGitConfig := GitConfig(trickleDbURI.value),     // Used mostly to configure authentication
trickleBranch := "master",                             // Branch in which to store the metadata
```

## Authentication

The metadata central repository can be specified with either ssh or https protocols, like `git
@github.com:user/repo` or `https://github.com/user/repo`.

### HTTPS

Authentication for https has to be in the form of user/password, and, if no password is provided
and `TRICKLE_GITHUB_TOKEN` is present in the environment, that token will be used for password.
This is compatible with how github uses personal access tokens. User and password can be
provided using the standard URL syntax of `https://user:password@domain/`. The password part,
`:password`, is optional and not recommended. Instead, use a personal access token, or pass the
password through that environment variable.

It is also possible to specify user and password by setting `trickleGitConfig`. You can use the
helper `sbttrickle.git.GitConfig(remote, user, password)`, or create and use `CredentialsProvider`.

### SSH

The remote URI can be provided either in full URL format with `ssh` as the protocol, or in
abbreviated format like `git@github.com:user/repo`. Authentication can be performed with
either user and password, or using public/private keys.

User can be specified in the full URL, and password passed either in the full URL, which is not
recommended, or through the environment variable `TRICKLE_GITHUB_TOKEN`. Alternatively, it can
be passed by overriding `trickleGitConfig` and either using the `sbttrickle.git.GitConfig
(remote, user, password)` helper, or creating and using a `CredentialsProvider`.

By default, `~/.ssh/identity`, `~/.ssh/id_rsa` and `~/.ssh/id_dsa` will be looked up for
public/private keys, and must have an empty passphrase. If using another file or a passphrase,
override `trickleGitConfig` with:

```sbt
sbttrickle.git.GitConfig(trickleDbURI.value)
  .withIdentityFile(file("path"), Some(f => "passphrase"))
```

The passphrase parameter is optional.

In all cases, the public key file must have the same name as the private key file, with `.pub`
as extension. Newer openssh key formats, that start with `BEGIN OPENSSH PRIVATE KEY`, are not
supported. See troubleshooting session on how to proceed in that case.

## Usage

The main tasks are as follows:

```sbt
trickleUpdateSelf          // Update metadata repository with this project's information
trickleReconcile           // Creates pull requests to bump versions
trickleUpdateAndReconcile  // Performs both tasks above in sequence
```

The following tasks/commands are provided for sbt console usage:

```sbt
show trickleDotGraph       // Displays build topology graph in dot file format
```

## How does it work

TODO: topology example images

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

