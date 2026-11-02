# MP4Parser keep rules as specified in spec.md Section 10.1
-keep class com.coremedia.iso.boxes.** { *; }
-keep class com.googlecode.mp4parser.** { *; }
-keep class com.mp4parser.** { *; }

# Suppress desktop-only Java AWT / ImageIO references from mp4parser
-dontwarn java.awt.image.BufferedImage
-dontwarn javax.imageio.ImageIO
-dontwarn com.googlecode.mp4parser.**
