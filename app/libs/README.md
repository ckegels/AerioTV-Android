# Vendored libraries

## media3-decoder-ffmpeg.aar

The Media3 FFmpeg audio-decoder extension. Google does not publish a prebuilt
artifact for it, so this AAR is built from source and vendored here. It provides
software decoding for audio codecs that many devices have no hardware MediaCodec
for, notably **AC-3, E-AC-3 and MP2** carried by US ATSC broadcast channels and
by surround VOD titles. Without it,
ExoPlayer reports "no audio tracks" and plays silent on boxes like the
Chromecast with Google TV. It is wired in as the fallback audio renderer
(`EXTENSION_RENDERER_MODE_ON`) in `core/playback/AerioRenderers.kt`.

### Enabled decoders (2026-09-14)

```
ac3 eac3 dca truehd mlp mp2 mp3 flac alac
```

**Why this set.** AerioTV matches what VLC and Kodi ship (Logan, 2026-09-14).

2026-09-11 had dropped `eac3`, `dca`, `mlp` and `truehd` over patent exposure.
That was reverted on 2026-09-14 after the regression it caused: on a Shield TV,
a VOD title with E-AC-3 5.1 and **Surround Sound Passthrough OFF** played video
normally with completely silent audio. The mechanism: with passthrough off,
`aerioRenderersFactory` builds the sink with `DEFAULT_AUDIO_CAPABILITIES`
(PCM only), and the Shield platform exposes E-AC-3 for **bitstream** only, so
`MediaCodecAudioRenderer` declines the format; the track fell through to the
FFmpeg renderer behind it, which no longer carried an `eac3` decoder and
declined it too, leaving the track with NO renderer at all. The four are back
and the patent position is handled by notice instead: see the **Patents**
section of the repo `README.md`, `THIRD_PARTY_LICENSES.md`, and the in-app
Settings > About > Open Source Licenses screen.

2026-09-12 dropped `aac` and that still stands. AAC's essential patents have
expired, but every Android device the app supports has a hardware AAC decoder
(AAC is mandatory in the Android CDD), so a software AAC decoder in the binary
was never reached on real hardware and only added size.

Behavioral consequences:

- AC-3, E-AC-3, DTS, TrueHD/MLP, MP2, MP3, FLAC and ALAC all have a software
  fallback. The platform decoder still wins whenever it can actually produce
  PCM (`EXTENSION_RENDERER_MODE_ON`); FFmpeg only picks up what the platform
  refuses, which includes the bitstream-only case above.
- AAC, including HE-AAC / AAC+ (SBR) on-demand recordings, relies entirely on
  the device's hardware decoder. This is the GH #45 path: the on-demand
  renderer mode stays `EXTENSION_RENDERER_MODE_PREFER`, but with no software
  AAC in the build the FFmpeg renderer no longer advertises the MIME, so every
  AAC track falls through to MediaCodec. See the note in
  `core/playback/AerioRenderers.kt`.
- Which renderer actually won is logged on every tune by the always-on
  `audio renderer -> ...` line in `AerioExoPlayerHolder`, naming the decoder
  (FFmpeg decoder names start with `ffmpeg`).

`FfmpegLibrary.supportsFormat` is a runtime query against the native library,
so nothing in the app needed a MIME allow-list change; the Cast on-phone
transcoder (`CastAudioTranscoder`) likewise just stops finding an FFmpeg
fallback for these formats and refuses the session when the phone also has no
platform decoder.

### How it was built (reproducible)

- media3 checkout: tag `1.4.1` (matches the `media3` version in
  `gradle/libs.versions.toml`)
- ffmpeg: `release/6.0` (n6.0.x) cloned into
  `libraries/decoder_ffmpeg/src/main/jni/ffmpeg`
- NDK `25.1.8937393`, cmake `3.31.x`, nasm (for the x86_64 build)
- module `minSdkVersion` raised to 21 so every ABI links at >= 21 (the app's
  own minSdk is 26)
- `libraries/decoder_ffmpeg/src/main/jni/CMakeLists.txt` patched with
  `target_link_options(ffmpegJNI PRIVATE "-Wl,-z,max-page-size=16384")` so the
  .so links with 16 KB ELF page alignment (Android 15+/16 requirement; NDK r26
  and below default to 4 KB and Android 16 then runs the app in "page size
  compatible mode" with a launch warning). The ffmpeg static libs need no
  rebuild for this; alignment is fixed at the final shared-object link. Verify
  with `llvm-readelf -l libffmpegJNI.so`: every LOAD segment must show align
  `0x4000`.

```
# in the media3 checkout, from libraries/decoder_ffmpeg/src/main/jni
./build_ffmpeg.sh "<repo>/libraries/decoder_ffmpeg/src/main" \
  "$ANDROID_SDK/ndk/25.1.8937393" darwin-x86_64 21 \
  ac3 mp2 mp3 flac alac eac3 dca truehd mlp
# then, from the media3 checkout root (ANDROID_HOME must be set)
./gradlew :lib-decoder-ffmpeg:assembleRelease
# output: libraries/decoder_ffmpeg/buildout/outputs/aar/lib-decoder-ffmpeg-release.aar
```

The .so is stripped, so verify the codec set with `strings` rather than `nm`:
`ff_ac3_decoder`, `ff_eac3_decoder`, `ff_dca_decoder`, `ff_truehd_decoder`,
`ff_mlp_decoder`, `ff_mp2_decoder`, `ff_mp3_decoder`, `ff_flac_decoder` and
`ff_alac_decoder` must each hit, and `ff_aac_decoder` must miss, on each of the
four ABIs. Verified on all four for the 2026-09-14 build (AAR 1.6 MB to 2.4 MB),
alongside `llvm-readelf -l` showing align `0x4000` on every LOAD segment.

Rebuild and replace this file when bumping the `media3` version so the extension
stays binary-compatible with the maven media3 artifacts. Keep the decoder list
above; do not re-add `aac`.
