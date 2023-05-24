This directory, which is not incorporated into the Gradle project, holds assets that should not
be included in commonMain/resources or jvmMain/resources.  For now, that consists of icons used
for platform-specific builds of the desktop app, along with their source materials.

## Icons

App icons are located here.  Our logo icon is derived from logo.svg in various ways.

### macOS

This is by far the most complicated procedure.  From logo.svg, we've manually crafted an Iconset.
We used Affinity Designer, but any featureful graphics editor should work.  Iconsets folders containing
an Apple-specified set of PNGs, each with a different size.  I don't know an automated way to generate
these comfortably from an SVG, so we did it by hand.

From there, Apple supplies a program called `iconutil` as part of their Xcode developer tools.  Use
it thusly:

```zsh
iconutil -c icns logo.iconset
```

### Windows

In contrast, Windows ICO files are simplicity itself.  We used Imagemagick's `convert` tool:

```zsh
convert logo.iconset/icon_128x128.png windows.ico
```

Note that the source here is the 128x128 PNG from the macOS iconset.  This is because, for
reasons I haven't investigated, using the SVG does not produce a transparent background.

### Linux

As with Windows, we'll use the 128x128 macOS PNG as our source.  In fact, we'll just use it as-is.

```zsh
cp logo.iconset/icon_128x128.png linux.png
```
