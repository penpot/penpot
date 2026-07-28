;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.wasm.gfonts
  "Compile-time Google Fonts catalog for the headless exporter, built from the
  same `gfonts.json` the browser uses via the `preload-gfonts` .clj macro (a
  macro namespace, so requiring it does not pull in `app.main.fonts` itself).

  `parse-gfont` assigns each font a `uuid/random` at macro-expansion time, so
  this catalog MUST stay the single shared instance: `app.wasm.text` maps
  `gfont-<slug>` to the uuid and `app.renderer.wasm` maps it back to a ttf url."
  (:require-macros [app.main.fonts :refer [preload-gfonts]]))

(def ^:private catalog
  (preload-gfonts "fonts/gfonts.2025.11.28.json"))

(def ^:private by-id
  (reduce (fn [m font] (assoc m (:id font) font)) {} catalog))

(def ^:private by-uuid
  (reduce (fn [m font] (assoc m (:uuid font) font)) {} catalog))

(defn gfont-id->uuid
  "Maps a `gfont-<slug>` id to its (build-stable) catalog uuid, or nil."
  [gfont-id]
  (:uuid (get by-id gfont-id)))

(defn resolve-ttf-url
  "Given a google font uuid + numeric weight + style int (0 = normal, else
  italic), returns the variant's gstatic ttf url (or nil if not a google font /
  no variants). Degrades weight+style -> weight -> first variant."
  [font-uuid weight style]
  (when-let [font (get by-uuid font-uuid)]
    (let [w        (str weight)
          s        (if (zero? style) "normal" "italic")
          variants (:variants font)
          variant  (or (some (fn [v] (when (and (= (:weight v) w) (= (:style v) s)) v)) variants)
                       (some (fn [v] (when (= (:weight v) w) v)) variants)
                       (first variants))]
      (:ttf-url variant))))
