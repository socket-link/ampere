# Releasing AMPERE

This document describes how to publish new versions of AMPERE to Maven Central.

## Prerequisites

### 1. Maven Central Portal Account

1. Create account at https://central.sonatype.com
2. Register the `link.socket` namespace
3. Generate a user token at https://central.sonatype.com/account

### 2. GPG Key Setup

```bash
# Generate key (choose RSA 4096, no expiration for releases)
gpg --full-generate-key

# List keys to find key ID (last 8 characters of the long ID)
gpg --list-secret-keys --keyid-format LONG

# Upload to key server (required by Maven Central)
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
```

### 3. Local Credentials

Add to `~/.gradle/gradle.properties`:

```properties
mavenCentralUsername=your-token-username
mavenCentralPassword=your-token-password
signingInMemoryKeyId=ABCD1234
signingInMemoryKey=exported-ascii-armored-key
signingInMemoryKeyPassword=your-gpg-passphrase
```

### 4. GitHub Secrets (for CI)

Add these repository secrets:

| Secret | Description |
|--------|-------------|
| `MAVEN_CENTRAL_USERNAME` | Maven Central Portal token username |
| `MAVEN_CENTRAL_PASSWORD` | Maven Central Portal token password |
| `SIGNING_KEY_ID` | Last 8 chars of GPG key ID |
| `SIGNING_KEY` | ASCII-armored GPG private key |
| `SIGNING_PASSWORD` | GPG key passphrase |

To export the signing key for CI:
```bash
gpg --armor --export-secret-keys YOUR_KEY_ID
```

## Version Numbering

AMPERE follows [Semantic Versioning](https://semver.org/):
- MAJOR: Breaking API changes
- MINOR: New features, backward compatible
- PATCH: Bug fixes, backward compatible

## Release Process

### Automated Release (Recommended)

1. Update version in `gradle.properties`:
   ```properties
   ampereVersion=0.1.0
   ```

2. Commit and tag:
   ```bash
   git add gradle.properties
   git commit -m "Release 0.1.0"
   git tag v0.1.0
   git push origin main --tags
   ```

3. The CI workflow will automatically:
   - Validate the tag matches the version in gradle.properties
   - Run tests
   - Publish to Maven Central staging
   - Create a GitHub Release

4. The vanniktech plugin publishes directly via the Maven Central Portal API.
   Monitor status at https://central.sonatype.com/publishing

### Manual Release

If CI fails or you need to publish manually:

```bash
./gradlew :ampere-core:publishAllPublicationsToMavenCentralRepository \
  -PmavenCentralUsername=YOUR_TOKEN_USERNAME \
  -PmavenCentralPassword=YOUR_TOKEN_PASSWORD \
  -PsigningInMemoryKeyId=KEY_ID \
  -PsigningInMemoryKey="$(gpg --armor --export-secret-keys KEY_ID)" \
  -PsigningInMemoryKeyPassword=KEY_PASSPHRASE
```

## Prepare Next Development Cycle

After releasing:

```bash
# Bump to next snapshot version
# Change: ampereVersion=0.1.0
# To:     ampereVersion=0.2.0-SNAPSHOT

git add gradle.properties
git commit -m "Prepare next development iteration"
git push origin main
```

## Verifying Release

After release, artifacts appear on Maven Central within ~30 minutes:
- https://search.maven.org/search?q=g:link.socket
- https://repo1.maven.org/maven2/link/socket/ampere/

## Troubleshooting

### Publishing not appearing
Wait a few minutes and check https://central.sonatype.com/publishing. The Portal can be slow.

### Signature verification failed
Ensure your GPG public key is published to `keyserver.ubuntu.com`:
```bash
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
```

### POM validation failed
Generate and inspect the POM:
```bash
./gradlew :ampere-core:generatePomFileForKotlinMultiplatformPublication
cat ampere-core/build/publications/kotlinMultiplatform/pom-default.xml
```

### Version mismatch error in CI
Ensure the git tag matches the version in `gradle.properties` exactly.
