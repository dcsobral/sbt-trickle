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
// Update metadata repository with this project's information
trickleUpdateSelf

// Creates pull requests to bump versions
trickleCreatePullRequests

// Log what needs to be updated (default action of trickleCreatePullRequest)
trickleLogUpdatableRepositories
```

Pull request creation can use the following input task to make sure the dependencies
were properly updated. If any differing revision is found, it will fail.

```sbt-console
trickleCheckVersion org:name:revision {...}
```

The following tasks/commands are provided for sbt console usage:

```sbt-console
trickleLogUpdatableRepositories // Shows what needs updating
show trickleBuildTopology       // Displays build topology graph in dot file format
trickleSaveGraph <file>         // Saves a dot file of the build graph topology
trickleOpenGraph <file>         // Displays build graph topology (requires graphviz)
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
  /* Basic configuration */

  // Centralized metadata database locator
  // mandatory
  trickleDbURI in ThisBuild := "<url>", // eg, git repository clone url

  // Information about your project repository
  // defaults to URI's path, normalized for module name rules
  trickleRepositoryName in ThisBuild := "<unique name>",

  // Information provided to PR creation and check
  // defaults to scmInfo.browseUrl or homepage
  trickleRepositoryURI in ThisBuild := "<url>",

  /* Auto bump */

  // Function which creates the autobump pull requests
  // defaults to logging what needs bumping
  trickleCreatePullRequest := (??? : OutdatedRepository => Unit),

  // Function which checks if an outdated repository has outstanding autobump pull requests
  // defaults to using trickleGithubIsAutobumpPullRequest
  trickleIsAutobumpPullRequestOpen := (??? : OutdatedRepository => Boolean),

  // Function which checks if a pull request is an autobump pull request on Github
  // defaults to always returning false, so beware
  trickleGithubIsAutobumpPullRequest := (??? : PullRequest => Boolean),

  /* Optional settings */

  // If set to true, does not update remote or create pull requests
  // defaults to not set, so it won't override trickleGitConfig values
  trickleDryMode := false,

  // Used to configure git options such as remote and authentication
  trickleGitConfig := GitConfig(trickleDbURI.value).withBranch(trickleGitBranch.?.value),

  // Branch to be used in the metadata repository
  // defaults to not set, in which case "master" is used
  trickleGitBranch := "master"
)
```

## Authentication

The metadata central repository can be specified with either ssh or https protocols, like `git
@github.com:user/repo` or `https://github.com/user/repo`.

### HTTPS

Authentication for https has to be in the form of user/password, provided either through the URL
or through the environment variables `TRICKLE_USER` and `TRICKLE_PASSWORD`, which are used as
fallback.

This is compatible with how github uses personal access tokens. User and password can be
provided using the standard URL syntax of `https://user:password@domain/`. The password part,
`:password`, is optional and not recommended. Instead, pass the personal access token or
password through the environment variable.

It is also possible to specify user and password by setting `trickleGitConfig` with user and
password, or creating and using a `CredentialsProvider`.

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
override `trickleGitConfig`.

In all cases, the public key file must have the same name as the private key file, with `.pub`
as extension. Newer openssh key formats, that start with `BEGIN OPENSSH PRIVATE KEY`, are not
supported. See troubleshooting session on how to proceed in that case.

### Example

```sbt
import sbttrickle.TricklePlugin.autoImport._
import sbttrickle.git.GitConfig

trickleGitConfig := {
  val remote = trickleDbURI.value
  val config =
    if (remote.startsWith("https:")) {
      (sys.env.get("GITHUB_ACTOR"), sys.env.get("GITHUB_TOKEN")) match {
        case (Some(user), Some(token)) => GitConfig(remote, user, token)
        case _                         => GitConfig(remote)
      }
  } else {
      (sys.env.get("SSH_IDENTITY"), sys.env.get("SSH_PASSPHRASE")) match {
        case (Some(identity), Some(passphrase)) => GitConfig(remote)
          .withIdentityFile(file(identity), Some((_: File) => passphrase))
        case (Some(identity), None)             => GitConfig(remote)
          .withIdentityFile(file(identity))
        case _                                  => GitConfig(remote)
      }
  }
  config.withBranch(trickleGitBranch.?.value)
}

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

```bash
ssh-keygen -p -f file -m pem
```

## Trivia

The name was inspired by the Mammoth Terraces on Yellowstone National Park, but
sbt-terraces just didn't evoke the right image.

