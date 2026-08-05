# Publishing

Two separate things live under this heading: getting the source onto GitHub, and
getting a signed build into someone's hands. They have different risks.

---

## Part 1 — Putting the repository on GitHub

### First time, from a local clone

```bash
cd FlexAndroid
git init -b main
git add .
git commit -m "Initial commit: FlexRadio SmartLink client for Android"
```

Then create the remote. With the [GitHub CLI](https://cli.github.com/):

```bash
gh auth login
gh repo create gauthig/FlexAndroid --public --source=. --remote=origin --push
```

Without `gh`, create the empty repository in the browser first — **no README, no
`.gitignore`, no licence**, since those already exist here and would collide — then:

```bash
git remote add origin https://github.com/gauthig/FlexAndroid.git
git push -u origin main
```

### Check what you are about to publish

Do this before the first push, not after. A secret that reaches GitHub is public even
if the commit is later removed, because forks and caches keep it.

```bash
# What is actually staged?
git status --short

# Nothing that looks like a key or keystore?
git ls-files | grep -iE '\.(jks|keystore|p12|pem|key)$|local\.properties|signing'

# No token- or password-shaped strings in the tree?
git grep -nEi '(api[_-]?key|secret|password|token)\s*[:=]\s*["'"'"'][^"'"'"']{12,}'
```

The last one will match the Auth0 client ID in `SmartLinkClient`. **That is not a
secret** — it is a public application identifier, the same one every FlexRadio client
presents, and it identifies the app rather than any user. There is no secret in this
repository, and the `.gitignore` excludes keystores and `local.properties` so there
should never be.

### Things worth doing on the repository afterwards

- Add topics: `android`, `kotlin`, `ham-radio`, `amateur-radio`, `sdr`, `flexradio`,
  `smartsdr`, `smartlink`, `vita49`, `jetpack-compose`.
- Set the description to one line: *Remote control of a FlexRadio SDR from Android
  over SmartLink.*
- Protect `main`: require the `build` check to pass before merging. The workflow in
  `.github/workflows/android-ci.yml` provides it.
- Leave Discussions off until there is someone to discuss with.

---

## Part 2 — Signed release builds

`assembleRelease` produces an unsigned APK. Nothing in this repository references a
keystore, deliberately: cloning and building can never sign with someone else's key.

### Generate a signing key

Once, and then guard it. If you lose it you cannot ship an update that Android will
accept as this app; if someone else gets it they can.

```bash
keytool -genkeypair -v \
  -keystore flexandroid-release.jks \
  -alias flexandroid \
  -keyalg RSA -keysize 4096 -validity 10000
```

Keep the `.jks` **outside the repository**. `.gitignore` already excludes `*.jks`,
`*.keystore` and `keystore.properties`, but the safest place is somewhere `git add`
cannot reach.

### Wire it up without committing anything

Create `keystore.properties` in the project root — it is git-ignored:

```properties
storeFile=/absolute/path/to/flexandroid-release.jks
storePassword=…
keyAlias=flexandroid
keyPassword=…
```

Then add this to `app/build.gradle.kts`. It is not committed, because a signing block
that silently does nothing is worse than none at all:

```kotlin
val keystoreProperties = java.util.Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    signingConfigs {
        create("release") {
            // Only configured when the properties file is present, so a plain
            // clone still builds — it just produces an unsigned APK.
            keystoreProperties.getProperty("storeFile")?.let {
                storeFile = file(it)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (keystoreProperties.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}
```

```bash
./gradlew assembleRelease
```

### Signing in CI

If you want tagged releases built by Actions, store the keystore as a base64 secret
and reconstruct it in the job:

```bash
base64 -w0 flexandroid-release.jks    # paste into the RELEASE_KEYSTORE secret
```

Secrets needed: `RELEASE_KEYSTORE`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
`RELEASE_KEY_PASSWORD`. `.github/workflows/release.yml` builds an unsigned APK on
tag by default and has the signing step commented out with these names, so enabling
it is uncommenting rather than writing.

Never `echo` a secret, and never let one reach a build log. GitHub masks known secret
values, but only exact matches — a base64 fragment or a substring will not be masked.

### GPL-3.0 obligations

If you distribute a binary, the licence requires that recipients can get the
corresponding source for **that build**. In practice:

- Tag the commit you built from, and attach the APK to that tag's release. The tag is
  the correspondence.
- Keep the `LICENSE` file in the repository, and the per-file headers in place.
- If someone else's GPL code is ever vendored in, its notices come too.

Distributing on Google Play is compatible with GPL-3.0, but Play's own terms and the
licence both apply — and an app that transmits on amateur bands will need to say
something to Play review about who is permitted to use it.
