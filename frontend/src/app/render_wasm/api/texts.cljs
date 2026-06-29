;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.render-wasm.api.texts
  (:require
   [app.render-wasm.api.fonts :as f]
   [app.render-wasm.text-content :as tc]))

(defn write-shape-text
  "Workspace text serialization: the byte writing is shared via
  `app.render-wasm.text-content`; font resolution is the workspace's (fonts DB)."
  [spans paragraph text]
  (tc/write-shape-text! spans paragraph text
                        {:normalize-font-id   f/normalize-font-id
                         :normalize-paragraph f/normalize-paragraph-font
                         :normalize-span      f/normalize-span-font}))

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

