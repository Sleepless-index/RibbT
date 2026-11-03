# A lean ffmpeg for RibbT, built with the Termux package builder (see .github/workflows/ffmpeg-lite.yml).
# It is Termux's own ffmpeg recipe cut down to what RibbT uses: H.264 (x264), AAC, MP3, Opus, WebP, AV1
# decoding (dav1d) and HTTPS. The AV1, H.265 and VP9 encoders and everything else are left out.
TERMUX_PKG_HOMEPAGE=https://ffmpeg.org
TERMUX_PKG_DESCRIPTION="Lean ffmpeg for RibbT"
TERMUX_PKG_LICENSE="GPL-3.0"
TERMUX_PKG_MAINTAINER="@termux"
TERMUX_PKG_VERSION="9.0.2"
TERMUX_PKG_REVISION=1
TERMUX_PKG_SRCURL="https://www.ffmpeg.org/releases/ffmpeg-${TERMUX_PKG_VERSION}.tar.xz"
TERMUX_PKG_SHA256=8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e
TERMUX_PKG_DEPENDS="libdav1d, libmp3lame, libopus, libwebp, libx264, openssl, zlib"
TERMUX_PKG_BREAKS="ffmpeg-dev"
TERMUX_PKG_REPLACES="ffmpeg-dev"
TERMUX_PKG_RM_AFTER_INSTALL="
bin/ffplay
share/doc/ffmpeg/ffplay*
share/man/man1/ffplay*
"

termux_step_configure() {
	cd $TERMUX_PKG_BUILDDIR

	$TERMUX_PKG_SRCDIR/configure \
		--arch="aarch64" \
		--as="$AS" \
		--cc="$CC" \
		--cxx="$CXX" \
		--nm="$NM" \
		--ar="$AR" \
		--ranlib="llvm-ranlib" \
		--pkg-config="$PKG_CONFIG" \
		--strip="$STRIP" \
		--cross-prefix="${TERMUX_HOST_PLATFORM}-" \
		--disable-autodetect \
		--disable-indevs \
		--disable-outdevs \
		--disable-doc \
		--disable-ffplay \
		--disable-static \
		--disable-symver \
		--enable-cross-compile \
		--enable-gpl \
		--enable-version3 \
		--enable-neon \
		--enable-zlib \
		--enable-libdav1d \
		--enable-libmp3lame \
		--enable-libopus \
		--enable-libwebp \
		--enable-libx264 \
		--enable-openssl \
		--enable-shared \
		--prefix="$TERMUX_PREFIX" \
		--target-os=android
}
