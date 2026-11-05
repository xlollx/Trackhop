#!/usr/bin/env bash
# The brand surfaces, from one set of parameters: the app icon as PNG (store, site), the feature
# graphics (EN and IT, Play and F-Droid), GitHub's social preview and, on request, previews for review.
#
#   scripts/store_graphics.sh            # writes the icon, the feature graphics and the square banners into the repo
#   scripts/store_graphics.sh preview DIR  # also icon previews (48/108/512 px, themed) into DIR
#
# Needs ImageMagick 6 (`convert`) and the DejaVu fonts. No Android SDK: the icon's PNGs are drawn
# here from the same geometry as the vector drawables (res/drawable/ic_launcher_*.xml), so they
# must be kept in step by hand when one changes.
#
# The design, "the beam": a near-black base crossed by a luminous diagonal band. Across the band
# (direction n, perpendicular to its edge, positive towards the bright side; distances in units of
# the surface's height H) the colour runs through BEAM_STOPS: black, a deep red glow up to the
# edge, a thin violet-blue line, cyan, then white. Along the band a red sweep (Screen blend, black
# on the left to BEAM_SWEEP on the right) turns the cyan to white and the glow brighter towards the
# right. The same stops are in the site CSS (--beam) and in the app (ui/Theme.kt, Brand.beam).
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
command -v convert >/dev/null || { echo "ImageMagick (convert) is required" >&2; exit 1; }
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

# ----------------------------------------------------------------------------------------- design
BEAM_ANGLE=12                          # the band's tilt, degrees clockwise from horizontal
# s (in H) and colour, in order; the band's edge is at s = 0.
BEAM_STOPS="-0.45 #000000 -0.008 #400000 0.008 #421AFF 0.08 #59FFFF 0.61 #FFFFFF"
BEAM_SWEEP="#990000"                   # right end of the along-band Screen overlay (left: black)
TILE_FILL="rgba(255,255,255,0.18)"     # the glass tile
TILE_LINE="rgba(255,255,255,0.35)"     # its faint lighter inner edge (0.4 of 54 tile units, ≥ 1 px)
TILE_RADIUS=0.24                       # corner radius, as a fraction of the tile's side
MARK_PER_TILE=1.3                      # the mark's 108-unit box, as a multiple of the tile's side
FONT_BOLD=DejaVu-Sans-Bold
FONT=DejaVu-Sans

# beam OUT W H AX AY SWEEP: the background, W×H, with the band's edge through (AX,AY).
# SWEEP is the colour at the right end of the along-band overlay, or "none" for the icon, where a
# VectorDrawable cannot blend and the band is short enough not to need it.
beam() {
  local out=$1 w=$2 h=$3 ax=$4 ay=$5 sweep=$6
  local nx ny lut=() s0 c0 s1 c1 prev_s="" prev_c=""
  nx=$(awk "BEGIN{print sin($BEAM_ANGLE*atan2(0,-1)/180)}")
  ny=$(awk "BEGIN{print -cos($BEAM_ANGLE*atan2(0,-1)/180)}")
  # The look-up strip: one segment per pair of stops, 1000 px per H.
  set -- $BEAM_STOPS
  local first_s=$1 last_s
  while [ $# -ge 2 ]; do
    s1=$1; c1=$2; shift 2
    if [ -n "$prev_s" ]; then
      lut+=( -size 1x$(awk "BEGIN{printf \"%d\", ($s1-($prev_s))*1000}") "gradient:$prev_c-$c1" )
    fi
    prev_s=$s1; prev_c=$c1
  done
  last_s=$prev_s
  local a_x a_y b_x b_y
  a_x=$(awk "BEGIN{print $ax+$nx*$first_s*$h}"); a_y=$(awk "BEGIN{print $ay+$ny*$first_s*$h}")
  b_x=$(awk "BEGIN{print $ax+$nx*$last_s*$h}");  b_y=$(awk "BEGIN{print $ay+$ny*$last_s*$h}")
  convert "${lut[@]}" -append "$tmp/lut.png"
  convert -size "${w}x${h}" xc:black -sparse-color barycentric "$a_x,$a_y black $b_x,$b_y white" \
    "$tmp/lut.png" -clut "$out"
  if [ "$sweep" != none ]; then
    convert "$out" \( -size "${w}x${h}" xc:black -sparse-color barycentric "0,0 black $w,0 $sweep" \) \
      -compose screen -composite "$out"
  fi
}

# mark OUT SIZE: the Trackhop mark (the paths of res/drawable/ic_brand_mark.xml, 108-unit box) in
# white on a transparent SIZE×SIZE canvas.
mark() {
  local out=$1 size=$2 k
  k=$(awk "BEGIN{print $2/108}")
  convert -size "${size}x${size}" xc:none -draw "scale $k,$k
    stroke white stroke-width 5.5 stroke-linecap round fill none
    path 'M30.6,40.5 A27,27 0 0 1 77.4,40.5'
    path 'M77.4,67.5 A27,27 0 0 1 30.6,67.5'
    stroke none fill white
    path 'M81.85,36.2 L71.45,42.2 L81.4,47.4 Z'
    path 'M26.15,71.8 L36.55,65.8 L26.6,60.6 Z'
    translate 2.1,1.5
    path 'M54.08,61.11 A7,5.6 -20 1 1 40.92,65.89 A7,5.6 -20 1 1 54.08,61.11 Z'
    stroke white stroke-width 3.6 stroke-linecap round fill none
    path 'M52.6,62.5 L52.6,37.5'
    stroke none fill white
    path 'M52.6,37.2 C58.5,38.6 63.4,42.4 63.2,49.6 C61.6,45.2 58,42.9 52.6,42.6 Z'" "$out"
}

# tile OUT SIDE: the glass tile with the mark centred on it, transparent around.
tile() {
  local out=$1 side=$2 r m sw half
  r=$(awk "BEGIN{print $side*$TILE_RADIUS}")
  sw=$(awk "BEGIN{w=$side*0.4/54; print (w<1)?1:w}"); half=$(awk "BEGIN{print $sw/2}")
  m=$(awk "BEGIN{printf \"%d\", $side*$MARK_PER_TILE}")
  mark "$tmp/tile_mark.png" "$m"
  convert -size "${side}x${side}" xc:none \
    -draw "fill $TILE_FILL stroke $TILE_LINE stroke-width $sw roundrectangle $half,$half $(awk "BEGIN{print $side-$half}"),$(awk "BEGIN{print $side-$half}") $r,$r" \
    "$tmp/tile_mark.png" -gravity center -composite "$out"
}

# contrast BG X Y W H: the WCAG contrast of white against the brightest pixel of that region.
contrast() {
  convert "$1" -crop "$4x$5+$2+$3" +repage -colorspace RGB -grayscale Rec709Luminance \
    -format "%[fx:1.05/(maxima+0.05)]" info:
}

# -------------------------------------------------------------------------------------------- icon
# The adaptive icon's 108-unit canvas, drawn at 10× : the beam with its edge through (72,36), so
# the band crosses the top-right corner of the tile and the mark sits on the dark side; the tile
# 54 units wide (corners 13) inside the 66-unit safe circle; the mark's box 70 units (scale 0.65).
icon_canvas() {
  local out=$1 k=10
  beam "$tmp/icon_bg.png" $((108*k)) $((108*k)) $((72*k)) $((36*k)) none
  tile "$tmp/icon_tile.png" $((54*k))
  convert "$tmp/icon_bg.png" "$tmp/icon_tile.png" -gravity center -composite "$out"
}

# icon_png OUT SIZE: the launcher's visible square (the central 72 of 108 units), opaque.
icon_png() {
  convert "$tmp/icon_canvas.png" -gravity center -crop 720x720+0+0 +repage -resize "${2}x${2}" -depth 8 -strip "$1"
}

# ------------------------------------------------------------------------------------- feature
# feature OUT TITLE LEAD SUB [W H]: 1024×500 by default (Play, F-Droid); 1280×640 is GitHub's social
# preview. The text on the left, on the dark side; the tile on the right, the band crossing its
# upper part as it crosses the icon's; a soft shadow under the text. Other sizes take the 1024×500
# layout scaled to fit (k) and centred vertically (dy), the tile kept 70 px (scaled) from the right.
# The contrast of each line against the brightest point under it is printed: keep it ≥ 4.5:1.
feature() {
  local out=$1 title=$2 lead=$3 sub=$4 w=${5:-1024} h=${6:-500}
  local k dy; k=$(awk "BEGIN{a=$w/1024; b=$h/500; print (a<b)?a:b}"); dy=$(awk "BEGIN{printf \"%d\", ($h-500*$k)/2}")
  px() { awk "BEGIN{printf \"%d\", $1*$k}"; }
  y() { echo $(( $(px "$1") + dy )); }
  local side tx ty; side=$(px 300); tx=$((w - $(px 70) - side)); ty=$(y 100)
  beam "$tmp/f_bg.png" $w $h $w "$(y 270)" "$BEAM_SWEEP"
  tile "$tmp/f_tile.png" "$side"
  convert "$tmp/f_bg.png" "$tmp/f_tile.png" -geometry "+$tx+$ty" -composite "$tmp/f_base.png"
  local lines=("title:$title:$FONT_BOLD:$(px 104):$(px 64):$(y 300)" "lead:$lead:$FONT:$(px 36):$(px 68):$(y 372)"
    "sub:$sub:$FONT:$(px 24):$(px 68):$(y 428)")
  local args=() l name text font size x y
  for l in "${lines[@]}"; do
    IFS=: read -r name text font size x y <<<"$l"
    args+=( -font "$font" -pointsize "$size" -annotate "+$x+$y" "$text" )
  done
  # The text alone, then its shadow (blurred, 55 %), then the text on top.
  convert -size "${w}x${h}" xc:none -fill white "${args[@]}" "$tmp/f_text.png"
  convert "$tmp/f_text.png" -fill black -colorize 100 -channel A -evaluate multiply 0.55 +channel \
    -blur 0x5 -repage +3+5 -background none -flatten "$tmp/f_shadow.png"
  convert "$tmp/f_base.png" "$tmp/f_shadow.png" -composite "$tmp/f_text.png" -composite -depth 8 -strip "$out"
  # Contrast of white against the brightest point under each line (background and shadow, no text).
  convert "$tmp/f_base.png" "$tmp/f_shadow.png" -composite "$tmp/f_under.png"
  for l in "${lines[@]}"; do
    IFS=: read -r name text font size x y <<<"$l"
    local box bw bh bx by
    box=$(convert -size "${w}x${h}" xc:none -fill white -font "$font" -pointsize "$size" -annotate "+$x+$y" "$text" -format "%@" info:)
    IFS='x+' read -r bw bh bx by <<<"$box"
    # The title and the lead stop short of the tile; the sub-line runs under it.
    local limit=$((tx - $(px 16))); [ "$name" = sub ] && limit=$((w - $(px 24)))
    [ $((bx + bw)) -le $limit ] || echo "  $(basename "$out"): $name is $((bx + bw - limit)) px too wide" >&2
    printf '  %s: %s: contrast %.1f:1\n' "$(basename "$out")" "$name" "$(contrast "$tmp/f_under.png" "$bx" "$by" "$bw" "$bh")"
  done
}

# -------------------------------------------------------------------------------------- square
# square OUT TITLE LEAD SUB: 1080×1080 for Instagram and other square feeds. The tile centred at
# the top, the band crossing its upper-right corner as on the icon; the three lines centred under
# it on the dark side, with the same soft shadow. The contrast of each line is printed: keep ≥ 4.5:1.
square() {
  local out=$1 title=$2 lead=$3 sub=$4 w=1080 h=1080 side=440
  local tx=$(( (w - side) / 2 )) ty=120
  beam "$tmp/q_bg.png" $w $h $w 360 "$BEAM_SWEEP"
  tile "$tmp/q_tile.png" $side
  convert "$tmp/q_bg.png" "$tmp/q_tile.png" -geometry "+$tx+$ty" -composite "$tmp/q_base.png"
  convert -size "${w}x${h}" xc:none -fill white -gravity North \
    -font "$FONT_BOLD" -pointsize 150 -annotate +0+610 "$title" \
    -font "$FONT" -pointsize 50 -annotate +0+810 "$lead" \
    -font "$FONT" -pointsize 32 -annotate +0+900 "$sub" "$tmp/q_text.png"
  convert "$tmp/q_text.png" -fill black -colorize 100 -channel A -evaluate multiply 0.55 +channel \
    -blur 0x6 -repage +4+6 -background none -flatten "$tmp/q_shadow.png"
  convert "$tmp/q_base.png" "$tmp/q_shadow.png" -composite "$tmp/q_text.png" -composite -depth 8 -strip "$out"
  convert "$tmp/q_base.png" "$tmp/q_shadow.png" -composite "$tmp/q_under.png"
  local name box bw bh bx by
  for name in title lead sub; do
    case $name in title) box="960x190+60+600" ;; lead) box="960x70+60+800" ;; sub) box="960x50+60+890" ;; esac
    IFS='x+' read -r bw bh bx by <<<"$box"
    printf '  %s: %s: contrast %.1f:1\n' "$(basename "$out")" "$name" "$(contrast "$tmp/q_under.png" "$bx" "$by" "$bw" "$bh")"
  done
}

# ------------------------------------------------------------------------------------------- main
icon_canvas "$tmp/icon_canvas.png"
icon_png "$root/docs/store/icon512.png" 512
cp "$root/docs/store/icon512.png" "$root/fastlane/metadata/android/en-US/images/icon.png"
echo "icon: docs/store/icon512.png, fastlane en-US icon.png"

feature "$root/docs/store/feature_en.png" "Trackhop" "Your playlists, on every service." "Free · Manual or scheduled sync · No subscription"
feature "$root/docs/store/feature_it.png" "Trackhop" "Le tue playlist, su ogni servizio." "Gratis · Manuale o programmata · Senza abbonamento"
cp "$root/docs/store/feature_en.png" "$root/fastlane/metadata/android/en-US/images/featureGraphic.png"
cp "$root/docs/store/feature_it.png" "$root/fastlane/metadata/android/it-IT/images/featureGraphic.png"
echo "feature graphics: docs/store/feature_{en,it}.png, fastlane en-US/it-IT featureGraphic.png"

# GitHub's social preview (Settings › General › Social preview), uploaded by hand.
feature "$root/docs/store/social_preview.png" "Trackhop" "Your playlists, on every service." "Free · Manual or scheduled sync · No subscription" 1280 640
echo "social preview: docs/store/social_preview.png"

# Square, for Instagram (also in the press kit).
square "$root/docs/store/square_en.png" "Trackhop" "Your playlists, on every service." "Free · Manual or scheduled sync · No subscription"
square "$root/docs/store/square_it.png" "Trackhop" "Le tue playlist, su ogni servizio." "Gratis · Manuale o programmata · Senza abbonamento"
echo "square: docs/store/square_{en,it}.png"

if [ "${1:-}" = preview ]; then
  dir=${2:?preview DIR}; mkdir -p "$dir"
  # The launcher as a phone shows it: the 72-unit square under a circular mask, at three sizes.
  for s in 48 108 512; do
    icon_png "$tmp/p_$s.png" "$s"
    convert "$tmp/p_$s.png" \( +clone -alpha extract -fill black -colorize 100 -fill white -draw "circle $((s/2)),$((s/2)) $((s/2)),1" \) \
      -alpha off -compose CopyOpacity -composite "$dir/icon_$s.png"
  done
  cp "$tmp/icon_canvas.png" "$dir/icon_canvas_1080.png"
  # The themed icon (Android 13): the monochrome layer, the mark alone, tinted on a tinted disc.
  mark "$tmp/mono.png" 1080
  convert -size 1080x1080 xc:none -fill '#E6DEFF' -draw "circle 540,540 540,180" \
    \( "$tmp/mono.png" -fill '#1B0062' -colorize 100 -resize 72% \) -gravity center -composite \
    -resize 192x192 "$dir/icon_themed.png"
  cp "$root/docs/store/feature_en.png" "$dir/feature_en.png"
  cp "$root/docs/store/feature_it.png" "$dir/feature_it.png"
  echo "previews in $dir"
fi
