;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.render-wasm.builtin-fonts
  "Fonts bundled with the frontend, served from `<public-uri>/fonts/`. Shared so
  the workspace and the exporter upload the same TTF for a given weight/style."
  (:require
   [app.common.render-wasm.gfonts :as gf]))

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
    (:ttf-url (or (gf/closest-variant variants weight (if (zero? style) "normal" "italic"))
                  (first variants)))))
