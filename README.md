# Minecraft for Project AIRI

`airicraft` is a Fabric mod project targeting Minecraft `1.21.11` with Java `21`, plus a `wrapper/` CLI subproject.

## Prerequisites

- `proto` (for Gradle)
- Java `21`
- A Java version manager (`jenv` on macOS in this guide)

This repository configures Gradle through `.prototools`:

```toml
[plugins.tools]
gradle = "https://raw.githubusercontent.com/eplightning/openjdk-adoptium-proto-plugin/main/build-plugins/gradle.toml"
```

<details>
<summary>macOS setup</summary>

0. Open Terminal
1. Install Gradle via `proto`

   ```shell
   proto install gradle
   ```

2. Install Java 21 via Homebrew

   ```shell
   brew install openjdk@21
   ```

   ```text
   ❯ brew install openjdk@21
   ==> Summary
   🍺  /opt/homebrew/Cellar/openjdk@21/21.0.10: 600 files, 347.2MB
   ```

3. Install `jenv` via Homebrew

   ```shell
   brew install jenv
   ```

4. Add `jenv` init to `~/.zshrc`

   ```shell
   echo 'export PATH="$HOME/.jenv/bin:$PATH"' >> ~/.zshrc
   echo 'eval "$(jenv init -)"' >> ~/.zshrc
   exec zsh
   ```

5. Enable `jenv` export plugin

   ```shell
   jenv enable-plugin export
   exec zsh
   ```

6. Register Homebrew JDK and set global version

   ```shell
   jenv add /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
   jenv versions
   jenv global openjdk64-21.0.10
   ```

   ```text
   ❯ jenv add /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
   openjdk64-21.0.10 added
   21.0.10 added
   21.0 added
   21 added
   ```

7. Verify Java and jenv

   ```shell
   java --version
   jenv versions
   jenv version
   jenv doctor
   ```

   ```text
   ❯ java --version
   openjdk 21.0.10 2026-01-20
   OpenJDK Runtime Environment Homebrew (build 21.0.10)
   OpenJDK 64-Bit Server VM Homebrew (build 21.0.10, mixed mode, sharing)
   ```

</details>

<details>
<summary>Windows setup</summary>

TODO: document Windows setup.

Suggested direction:

- Install Java 21
- Install and configure a Java version manager
- Install `proto` and run `proto install gradle`

</details>

<details>
<summary>Linux setup</summary>

TODO: document Linux setup.

Suggested direction:

- Install Java 21
- Install and configure a Java version manager
- Install `proto` and run `proto install gradle`

</details>

## Verify project build

Run these after Java is configured:

```shell
./gradlew --version
./gradlew build
```

For Minecraft dev client:

```shell
./gradlew runClient
```

```text
❯ ./gradlew runClient
The operation couldn’t be completed. Unable to locate a Java Runtime.
Please visit http://www.java.com for information on installing Java.
```

## Troubleshooting

### `./gradlew runClient` says `Unable to locate a Java Runtime`

Check:

```shell
java --version
which java
jenv version
jenv doctor
```

Fix:

1. Ensure `~/.zshrc` contains:
   - `export PATH="$HOME/.jenv/bin:$PATH"`
   - `eval "$(jenv init -)"`
2. Enable plugin and reload shell:
   - `jenv enable-plugin export`
   - `exec zsh`
3. Re-add JDK:
   - `jenv add /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
4. Re-select Java version:
   - `jenv global openjdk64-21.0.10`

### `jenv versions` only shows `system`

Cause: JDK not added into jenv, or shell init not loaded.

```shell
jenv add /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
exec zsh
jenv versions
```

### `JAVA_HOME` is empty

```shell
jenv enable-plugin export
exec zsh
env | grep JAVA_
```

If still empty, recheck `~/.zshrc` and run `jenv doctor`.

### `jenv` command not found

```shell
brew list jenv
cat ~/.zshrc | rg 'jenv'
exec zsh
which jenv
```
