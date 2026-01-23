# Releasing AMPERE

This document describes how to publish new versions of AMPERE to Maven Central.

## Prerequisites

### 1. Sonatype OSSRH Account

1. Create account at https://issues.sonatype.org
2. Create JIRA ticket requesting access to `link.socket` group ID
3. Wait for approval (usually 1-2 business days)

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
ossrhUsername=your-sonatype-username
ossrhPassword=your-sonatype-password
signing.keyId=ABCD1234
signing.password=your-gpg-passphrase
```

### 4. GitHub Secrets (for CI)

Add these repository secrets:

| Secret | Description |
|--------|-------------|
| `OSSRH_USERNAME` | Sonatype JIRA username |
| `OSSRH_PASSWORD` | Sonatype JIRA password |
| `SIGNING_KEY_ID` | Last 8 chars of GPG key ID |
| `SIGNING_KEY` | Base64-encoded GPG private key |
| `SIGNING_PASSWORD` | GPG key passphrase |

To export the signing key for CI:
```bash
gpg --armor --export-secret-keys YOUR_KEY_ID | base64
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

4. Release from staging:
   - Log in to https://s01.oss.sonatype.org
   - Navigate to Staging Repositories
   - Find your repository (linksocket-XXXX)
   - Click "Close" and wait for validation
   - Click "Release" if validation passes

### Manual Release

If CI fails or you need to publish manually:

```bash
./gradlew :ampere-core:publishAllPublicationsToOssrhRepository \
  -PossrhUsername=YOUR_USERNAME \
  -PossrhPassword=YOUR_PASSWORD \
  -Psigning.keyId=KEY_ID \
  -Psigning.password=KEY_PASSPHRASE
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

### Staging repository not found
Wait a few minutes and refresh. Sonatype can be slow.

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
