;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.colorpicker.hsl
  (:require
   [app.common.types.color :as cc]))

(defn components
  [{:keys [hsl r g b]}]
  (or hsl (cc/rgb->hsl [(or r 0) (or g 0) (or b 0)])))

(defn update-color
  [color property value]
  (let [index   (case property :h 0 :hsl-s 1 :hsl-l 2)
        hsl     (assoc (components color) index value)
        [r g b] (cc/hsl->rgb hsl)
        hex     (cc/rgb->hex [r g b])
        [h s v] (cc/hex->hsv hex)]
    ;; RGB cannot retain the chosen hue and saturation at black or white.
    {:hsl hsl :hex hex :h h :s s :v v :r r :g g :b b}))
