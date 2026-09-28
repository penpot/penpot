;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.fonts
  "Host-agnostic font knowledge shared by every renderer: the google catalog
  baked at compile time from `common/resources/fonts/gfonts.*.json`, the
  font-id/uuid mapping, weight/style variant resolution, and the noto fallback
  fonts a text's scripts and emoji need. Also the one family bundled with the
  frontend, which is not a google font but resolves by the same rules.

  Pure data + pure fns — no browser or Node dependencies — so the workspace and
  the headless exporter resolve the SAME fonts from the same source. Anything a
  host must fetch or upload for text to render belongs here, not in host code."
  (:require-macros [app.common.fonts :refer [preload-gfonts]])
  (:require
   [app.common.data :as d]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

;; --- GOOGLE FONTS CATALOG

(def catalog
  (preload-gfonts "fonts/gfonts.2025.11.28.json"))

(def ^:private by-id
  (reduce (fn [m font] (assoc m (:id font) font)) {} catalog))

(def ^:private by-uuid
  (reduce (fn [m font] (assoc m (:uuid font) font)) {} catalog))

(defn gfont-id->uuid
  "Maps a `gfont-<slug>` id to its (compilation-stable) catalog uuid, or nil."
  [gfont-id]
  (:uuid (get by-id gfont-id)))

;; --- font-id -> wasm uuid

(def ^:private custom-prefix "custom-")
(def ^:private gfont-prefix "gfont-")

(defn font-id->backend
  "Which source a content font-id comes from: `:google` for `gfont-<slug>`,
  `:custom` for `custom-<uuid>`, `:builtin` for everything else (bundled
  families, but also unknown or malformed ids — the same bucket
  `font-id->uuid` maps to `uuid/zero`)."
  [font-id]
  (cond
    (not (string? font-id))                  :builtin
    (str/starts-with? font-id gfont-prefix)  :google
    (str/starts-with? font-id custom-prefix) :custom
    :else                                    :builtin))

(defn font-id->uuid
  "Maps a content font-id to the uuid WASM keys fonts by:

   - `gfont-<slug>`   -> the catalog uuid,
   - `custom-<uuid>`  -> that uuid,
   - anything else (builtin, unknown, malformed) -> `uuid/zero`, which WASM
     resolves to the default font."

  [font-id]
  (case (font-id->backend font-id)
    :google (or (gfont-id->uuid font-id) uuid/zero)
    :custom (or (uuid/parse* (subs font-id (count custom-prefix))) uuid/zero)
    uuid/zero))

;; --- proxy urls

(def ^:private gstatic-prefix
  "https://fonts.gstatic.com/s")

(defn gstatic->proxy-url
  [s base]
  (let [base (str/rtrim (str base) "/")]
    (str/replace (str s) gstatic-prefix base)))

;; --- variant resolution

(defn closest-variant
  [variants target-weight target-style]
  (when-let [target-weight (d/parse-integer target-weight)]
    (let [result
          (reduce
           (fn [closest-match variant]
             (let [weight (d/parse-integer (:weight variant))
                   distance (abs (- target-weight weight))
                   matches-style? (= target-style (:style variant))
                   current {:variant variant
                            :weight weight
                            :distance distance}]
               (cond
                 ;; Exact match found
                 (and (zero? distance)
                      (if target-style matches-style? true))
                 (reduced current)

                 (nil? closest-match) current

                 ;; Update best match if this variant is closer or equal distance but higher weight
                 (or (< distance (:distance closest-match))
                     (and (= distance (:distance closest-match))
                          (> weight (:weight closest-match))))
                 current

                 ;; Same weight as the `closest-match` but the style matches `target-style`
                 (and (= weight (:weight closest-match)) matches-style?)
                 current

                 :else
                 closest-match)))
           nil
           variants)]
      (:variant result))))

(defn resolve-ttf-url
  [font-uuid weight style]
  (when-let [font (get by-uuid font-uuid)]
    (let [style    (if (zero? style) "normal" "italic")
          variants (:variants font)]
      (:ttf-url (or (closest-variant variants weight style)
                    (first variants))))))

;; --- BUILTIN FONTS
;;
;; Bundled with the frontend, served from `<public-uri>/fonts/`. Shared so the
;; workspace and the exporter upload the same TTF for a given weight/style.

(def local-fonts
  [{:id "sourcesanspro"
    :name "Source Sans Pro"
    :family "sourcesanspro"
    :variants
    [{:id "200" :name "200" :weight "200" :style "normal" :suffix "extralight" :ttf-url "sourcesanspro-extralight.ttf"}
     {:id "200italic" :name "200 Italic" :weight "200" :style "italic" :suffix "extralightitalic" :ttf-url "sourcesanspro-extralightitalic.ttf"}
     {:id "300" :name "300" :weight "300" :style "normal" :suffix "light" :ttf-url "sourcesanspro-light.ttf"}
     {:id "300italic" :name "300 Italic"  :weight "300" :style "italic" :suffix "lightitalic" :ttf-url "sourcesanspro-lightitalic.ttf"}
     {:id "regular" :name "400" :weight "400" :style "normal" :ttf-url "sourcesanspro-regular.ttf"}
     {:id "italic" :name "400 Italic" :weight "400" :style "italic" :ttf-url "sourcesanspro-italic.ttf"}
     {:id "600" :name "600" :weight "600" :style "normal" :suffix "semibold" :ttf-url "sourcesanspro-semibold.ttf"}
     {:id "600italic" :name "600 Italic" :weight "600" :style "italic" :suffix "semibolditalic" :ttf-url "sourcesanspro-semibolditalic.ttf"}
     {:id "bold" :name "700" :weight "700" :style "normal" :ttf-url "sourcesanspro-bold.ttf"}
     {:id "bolditalic" :name "700 Italic" :weight "700" :style "italic" :ttf-url "sourcesanspro-bolditalic.ttf"}
     {:id "black" :name "900" :weight "900" :style "normal" :ttf-url "sourcesanspro-black.ttf"}
     {:id "blackitalic" :name "900 Italic" :weight "900" :style "italic" :ttf-url "sourcesanspro-blackitalic.ttf"}]}])

(defn resolve-ttf-file
  "Builtin TTF file name for `weight` and `style` (0 normal, 1 italic), by the
  same nearest-weight rule as the google catalog."
  [weight style]
  (let [variants (:variants (first local-fonts))]
    (:ttf-url (or (closest-variant variants weight (if (zero? style) "normal" "italic"))
                  (first variants)))))

;; --- FALLBACK FONTS
;;
;; Which scripts/emoji a text uses and which (google) fallback fonts cover them.

(def ^:private emoji-pattern
  #"(?:\uD83C[\uDDE6-\uDDFF]\uD83C[\uDDE6-\uDDFF])|(?:\uD83C[\uDC00-\uDFFF]|\uD83D[\uDC00-\uDEFF])|(?:\uD83E[\uDD00-\uDDFF])|(?:\uD83D[\uDE80-\uDEFF]|\uD83E[\uDC00-\uDCFF])|(?:\uD83E[\uDE70-\uDFFF])|[\u2600-\u26FF\u2700-\u27BF\u2300-\u23FF\u2B00-\u2BFF]")

(def ^:private unicode-ranges
  {:japanese    #"[\u3040-\u30FF\u31F0-\u31FF\uFF66-\uFF9F]"
   :chinese     #"[\u4E00-\u9FFF\u3400-\u4DBF]"
   :korean      #"[\uAC00-\uD7AF]"
   :arabic      #"[\u0600-\u06FF\u0750-\u077F\u0870-\u089F\u08A0-\u08FF]"
   :cyrillic    #"[\u0400-\u04FF\u0500-\u052F\u2DE0-\u2DFF\uA640-\uA69F]"
   :greek       #"[\u0370-\u03FF\u1F00-\u1FFF]"
   :hebrew      #"[\u0590-\u05FF\uFB1D-\uFB4F]"
   :thai        #"[\u0E00-\u0E7F]"
   :devanagari  #"[\u0900-\u097F\uA8E0-\uA8FF]"
   :tamil       #"[\u0B80-\u0BFF]"
   :latin-ext   #"[\u0100-\u017F\u0180-\u024F]"
   :vietnamese  #"[\u1EA0-\u1EF9]"
   :armenian    #"[\u0530-\u058F\uFB13-\uFB17]"
   :bengali     #"[\u0980-\u09FF]"
   :cherokee    #"[\u13A0-\u13FF]"
   :ethiopic    #"[\u1200-\u137F]"
   :georgian    #"[\u10A0-\u10FF]"
   :gujarati    #"[\u0A80-\u0AFF]"
   :gurmukhi    #"[\u0A00-\u0A7F]"
   :khmer       #"[\u1780-\u17FF\u19E0-\u19FF]"
   :lao         #"[\u0E80-\u0EFF]"
   :malayalam   #"[\u0D00-\u0D7F]"
   :myanmar     #"[\u1000-\u109F\uAA60-\uAA7F]"
   :sinhala     #"[\u0D80-\u0DFF]"
   :telugu      #"[\u0C00-\u0C7F]"
   :tibetan     #"[\u0F00-\u0FFF]"
   :javanese    #"[\uA980-\uA9DF]"
   :kannada     #"[\u0C80-\u0CFF]"
   :oriya       #"[\u0B00-\u0B7F]"
   :mongolian   #"[\u1800-\u18AF]"
   :syriac      #"[\u0700-\u074F]"
   :tifinagh    #"[\u2D30-\u2D7F]"
   :coptic      #"[\u2C80-\u2CFF]"
   :ol-chiki    #"[\u1C50-\u1C7F]"
   :vai         #"[\uA500-\uA63F]"
   :shavian     #"\uD801[\uDC50-\uDC7F]"
   :osmanya     #"\uD801[\uDC80-\uDCAF]"
   :runic       #"[\u16A0-\u16FF]"
   :old-italic  #"\uD800[\uDF00-\uDF2F]"
   :brahmi      #"\uD804[\uDC00-\uDC7F]"
   :modi        #"\uD805[\uDE00-\uDE5F]"
   :sora-sompeng #"\uD804[\uDCD0-\uDCFF]"
   :bamum       #"[\uA6A0-\uA6FF]"
   :meroitic    #"\uD802[\uDD80-\uDD9F]"
   ;; Arrows, Mathematical Operators, Misc Technical, Geometric Shapes, Misc Symbols, Dingbats, Supplemental Arrows, etc.
   :symbols     #"[\u2190-\u21FF\u2200-\u22FF\u2300-\u23FF\u25A0-\u25FF\u2600-\u26FF\u2700-\u27BF\u2B00-\u2BFF]"
   ;; Additional symbol blocks covered by Noto Sans Symbols 2:
   ;; BMP: same as :symbols (arrows, math, misc symbols, dingbats, etc.)
   ;; SMP: Mahjong/Domino/Playing Cards (U+1F000-1F0FF), Supplemental Arrows-C (U+1F800-1F8FF),
   ;;      Legacy Computing Symbols (U+1FB00-1FBFF)
   :symbols-2   #"[\u2190-\u21FF\u2200-\u22FF\u2300-\u23FF\u25A0-\u25FF\u2600-\u26FF\u2700-\u27BF\u2B00-\u2BFF]|\uD83C[\uDC00-\uDCFF]|\uD83E[\uDC00-\uDCFF\uDF00-\uDFFF]"
   :music       #"[\u2669-\u267B]|\uD834[\uDD00-\uDD1F]"})

(defn contains-emoji? [text]
  (let [result (re-find emoji-pattern text)]
    (boolean result)))

(defn collect-used-languages
  [used text]
  (reduce-kv (fn [result lang pattern]
               (cond
                 ;; Skip regex operation if we already know that
                 ;; langage is present
                 (contains? result lang)
                 result

                 (re-find pattern text)
                 (conj result lang)

                 :else
                 result))
             used
             unicode-ranges))

(defn add-emoji-font
  [fonts]
  (conj fonts {:font-id "gfont-noto-color-emoji"
               :font-variant-id "regular"
               :style 0
               :weight 400
               :is-emoji true
               :is-fallback true}))

(def noto-fonts
  {:japanese    {:font-id "gfont-noto-sans-jp"            :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :chinese     {:font-id "gfont-noto-sans-sc"            :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :korean      {:font-id "gfont-noto-sans-kr"            :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :arabic      {:font-id "gfont-noto-sans-arabic"        :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :cyrillic    {:font-id "gfont-noto-sans"               :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :greek       {:font-id "gfont-noto-sans"               :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :hebrew      {:font-id "gfont-noto-sans-hebrew"        :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :thai        {:font-id "gfont-noto-sans-thai"          :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :devanagari  {:font-id "gfont-noto-sans"               :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :tamil       {:font-id "gfont-noto-sans-tamil"         :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :latin-ext   {:font-id "gfont-noto-sans"               :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :vietnamese  {:font-id "gfont-noto-sans"               :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :armenian    {:font-id "gfont-noto-sans-armenian"      :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :bengali     {:font-id "gfont-noto-sans-bengali"       :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :cherokee    {:font-id "gfont-noto-sans-cherokee"      :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :ethiopic    {:font-id "gfont-noto-sans-ethiopic"      :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :georgian    {:font-id "gfont-noto-sans-georgian"      :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :gujarati    {:font-id "gfont-noto-sans-gujarati"      :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :gurmukhi    {:font-id "gfont-noto-sans-gurmukhi"      :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :khmer       {:font-id "gfont-noto-sans-khmer"         :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :lao         {:font-id "gfont-noto-sans-lao"           :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :malayalam   {:font-id "gfont-noto-sans-malayalam"     :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :myanmar     {:font-id "gfont-noto-sans-myanmar"       :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :sinhala     {:font-id "gfont-noto-sans-sinhala"       :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :telugu      {:font-id "gfont-noto-sans-telugu"        :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :tibetan     {:font-id "gfont-noto-serif-tibetan"      :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :javanese    {:font-id "gfont-noto-sans-javanese"      :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :kannada     {:font-id "gfont-noto-sans-kannada"       :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :oriya       {:font-id "gfont-noto-sans-oriya"         :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :mongolian   {:font-id "gfont-noto-sans-mongolian"     :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :syriac      {:font-id "gfont-noto-sans-syriac"        :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :tifinagh    {:font-id "gfont-noto-sans-tifinagh"      :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :coptic      {:font-id "gfont-noto-sans-coptic"        :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :ol-chiki    {:font-id "gfont-noto-sans-ol-chiki"      :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :vai         {:font-id "gfont-noto-sans-vai"           :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :shavian     {:font-id "gfont-noto-sans-shavian"       :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :osmanya     {:font-id "gfont-noto-sans-osmanya"       :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :runic       {:font-id "gfont-noto-sans-runic"         :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :old-italic  {:font-id "gfont-noto-sans-old-italic"    :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :brahmi      {:font-id "gfont-noto-sans-brahmi"        :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :modi        {:font-id "gfont-noto-sans-modi"          :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :sora-sompeng {:font-id "gfont-noto-sans-sora-sompeng" :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :bamum       {:font-id "gfont-noto-sans-bamum"         :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :meroitic    {:font-id "gfont-noto-sans-meroitic"      :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :symbols     {:font-id "gfont-noto-sans-symbols"       :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :symbols-2   {:font-id "gfont-noto-sans-symbols-2"     :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}
   :music       {:font-id "gfont-noto-music"              :font-variant-id "regular" :style 0 :weight 400 :is-fallback true}})

(defn add-noto-fonts [fonts languages]
  (reduce (fn [acc lang]
            (if-let [font (get noto-fonts lang)]
              (conj acc font)
              acc))
          fonts
          languages))
