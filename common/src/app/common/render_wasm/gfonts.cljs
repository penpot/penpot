;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.render-wasm.gfonts
  (:require-macros [app.common.render-wasm.gfonts :refer [preload-gfonts]])
  (:require
   [app.common.data :as d]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

;; --- catalog

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

(defn font-id->uuid
  "Maps a content font-id to the uuid WASM keys fonts by:

   - `gfont-<slug>`   -> the catalog uuid,
   - `custom-<uuid>`  -> that uuid,
   - anything else (builtin, unknown, malformed) -> `uuid/zero`, which WASM
     resolves to the default font."

  [font-id]
  (cond
    (not (string? font-id))
    uuid/zero

    (str/starts-with? font-id gfont-prefix)
    (or (gfont-id->uuid font-id) uuid/zero)

    (str/starts-with? font-id custom-prefix)
    (or (uuid/parse* (subs font-id (count custom-prefix))) uuid/zero)

    :else
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
