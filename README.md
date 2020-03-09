# sbt-trickle

Trickles down updated dependencies across repositories through pull requests,
minimizing builds and maximizing parallelism.

It requires that all projects be running this plugin, and a location where the
global state can be recorded. A git connector is provided to record that state
on a remote git repository.

Published for SBT 1.3.8.

## Usage

The main tasks are as follows:

```sbt-console
trickleUpdateSelf          // Update metadata repository with this project's information
trickleCreatePullRequests  // Creates pull requests to bump versions
// To guarantee execution order, when called from another task, use:
Def.sequential(trickleUpdateSelf, trickleCreatePullRequests).value
```

The following tasks/commands are provided for sbt console usage:

```sbt-console
show trickleBuildTopology  // Displays build topology graph in dot file format
```

## Configuration

On `project/plugins.sbt`:

```sbt
addSbtPlugin("com.dcsobral" % "sbt-trickle" % "<version>")
```

On your `build.sbt` settings:

```sbt
import sbttrickle.TricklePlugin.autoImport._ // not needed
import sbttrickle.metadata.OutdatedRepository
import sbttrickle.git.GitConfig
import github4s.domain.PullRequest

lazy val trickleSettings: Seq[Def.Setting[_]] = Seq(
  // Information about your project repository
  trickleRepositoryName in ThisBuild := "<unique name>",
  // Information provided to PR creation and check
  trickleRepositoryURI in ThisBuild := "<url>",

  // Information about the metadata central repository
  trickleDbURI in ThisBuild := "<url>", // eg, git repository clone url

  // Auto bump
  // Function which creates the autobump pull requests;  defaults to logging what needs bumping
  trickleCreatePullRequest := (??? : OutdatedRepository => Unit),
  // Function which checks if a pull request is an autobump pull request
  trickleIsAutobumpPullRequest := (??? : PullRequest => Boolean),

  // Optional settings
  // If set to true, does not update remote
  // or create pull requests
  trickleDryMode := false,
  // Used to configure git options such as authentication
  trickleGitConfig := GitConfig(trickleDbURI.value),
  // Branch to be used in the metadata repository
  trickleGitBranch := "master"
)
```

## Authentication

The metadata central repository can be specified with either ssh or https protocols, like `git
@github.com:user/repo` or `https://github.com/user/repo`.

### HTTPS

Authentication for https has to be in the form of user/password, provided either through the URL
or through the environment variables `TRICKLE_USER` and `TRICKLE_PASSWORD`, which are used as
fallbacks.

This is compatible with how github uses personal access tokens. User and password can be
provided using the standard URL syntax of `https://user:password@domain/`. The password part,
`:password`, is optional and not recommended. Instead, pass the personal access token or
password through the environment variable.

It is also possible to specify user and password by setting `trickleGitConfig` with user and
password, or creating and using a `CredentialsProvider`. For example:

```sbt
import sbttrickle.TricklePlugin.autoImport._
import sbttrickle.git.GitConfig

trickleGitConfig := GitConfig(trickleDbURI.value, sys.env("GITHUB_ACTOR"), sys.env("GITHUB_TOKEN"))
```

### SSH

The remote URI can be provided either in full URL format with `ssh` as the protocol, or in
abbreviated format like `git@github.com:user/repo`. Authentication can be performed with
either user and password, or using public/private keys.

User name and password can be specified in the full URL, though it is not
recommended for passwords, or through the environment variables `TRICKLE_USER` and
`TRICKLE_PASSWORD`, which are used as fallbacks. Alternatively , it can
be passed by overriding `trickleGitConfig`, as seen on the HTTPS section above.

By default, `~/.ssh/identity`, `~/.ssh/id_rsa` and `~/.ssh/id_dsa` will be looked up for
public/private keys, and must have an empty passphrase. If using another file or a passphrase,
override `trickleGitConfig` with:

```sbt
import sbttrickle.TricklePlugin.autoImport._
import sbttrickle.git.GitConfig

trickleGitConfig := GitConfig(trickleDbURI.value)
  .withIdentityFile(file("path"), Some(f => "passphrase"))
```

The passphrase parameter is optional.

In all cases, the public key file must have the same name as the private key file, with `.pub`
as extension. Newer openssh key formats, that start with `BEGIN OPENSSH PRIVATE KEY`, are not
supported. See troubleshooting session on how to proceed in that case.

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

```bash
ssh-keygen -p -f file -m pem
```

## Trivia

The name was inspired by the Mammoth Terraces on Yellowstone National Park, but
sbt-terraces just didn't evoke the right image.

