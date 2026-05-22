# Add macOS aarch64 native Tor support and runtime ad-hoc signing

This adds native Tor support for Apple Silicon macOS by detecting `os.arch=aarch64` / `arm64`, mapping that platform to the `native/osx/aarch64/` Tor binary archive, and adding the `tor-binary-macos-aarch64` dependency.

## Why the signing step is needed

The macOS aarch64 Tor Expert Bundle contains arm64 Mach-O binaries, but the extracted `tor`, `libevent`, and pluggable transport binaries are not Apple code-signed. Locally, `codesign --verify` reports `code object is not signed at all`, and running the extracted `tor --version` on Apple Silicon is killed before it prints anything. In the demo apps this surfaced as:

```text
Could not setup Tor: java.io.IOException: Tor exited with value 137
```

Exit `137` is the Java-visible result of macOS killing the child process. After applying an ad-hoc signature to the extracted Mach-O files, the same `tor --version` command runs successfully.

The Tor Project publishes a macOS aarch64 Expert Bundle, and describes Expert Bundles as packages containing `tor` and pluggable transport binaries intended for developers who need to bundle Tor with their applications. The published archive has an external release signature, but that is not the same as Apple code signing each Mach-O binary inside the archive.

## Should upstream Tor sign these binaries?

There are two separate questions:

1. Archive authenticity: Tor already publishes detached signatures for the downloadable Expert Bundle archive.
2. Apple runtime acceptance: macOS may require an executable Mach-O code signature before running native Apple Silicon binaries.

For a developer-facing Expert Bundle, it is reasonable for upstream to leave final Apple signing to the downstream application bundle. The downstream app owner usually signs and notarizes the complete product they distribute. However, if the Expert Bundle is expected to run directly on Apple Silicon after extraction, upstream signing or explicit documentation of local ad-hoc signing would avoid this runtime failure.

## What key is used for ad-hoc signing?

No private key or certificate is used.

The implementation runs:

```sh
/usr/bin/codesign --force --sign - <file>
```

Apple documents `-` as the pseudo-identity for an ad-hoc signature. An ad-hoc signature does not use or record a cryptographic identity and identifies only the specific program being signed.

So this is not a Developer ID signature, does not assert Tor Project or Haveno identity, and does not add a certificate chain. It only gives macOS the local code-signing metadata it needs to launch the extracted arm64 Mach-O files.

## Does this affect reproducible builds?

It does not affect reproducibility of the published Maven/JAR artifacts, because signing happens only after runtime extraction into the local Tor working directory. The packaged `tor.tar.xz` resource remains unchanged.

It does mutate the extracted local files. Therefore, byte-for-byte comparisons of the runtime working directory after startup will differ from the original archive contents. Reproducibility checks should verify the packaged archive/dependency before runtime extraction, not the post-extraction working copy.

This is the least invasive fix: we keep the upstream archive intact, sign only local extracted Mach-O files, and scope the behavior to `MACOS_AARCH64` so Intel macOS, Linux, and Windows behavior remains unchanged.
