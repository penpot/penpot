;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.cursors
  (:require
   [app.common.uri :as u]
   [clojure.java.io :as io]
   [cuerdas.core :as str]))

(def cursor-folder "images/cursors")

(def default-hotspot-x 12)
(def default-hotspot-y 12)
(def default-rotation 0)
(def default-height 20)

(defn parse-svg [svg-data]
  (-> svg-data
      ;; Remove the <?xml ?> header
      (str/replace #"(?i)<\?xml[^\?]*\?>", "")

      ;; Remove comments
      (str/replace #"<\!\-\-(.*?(?=\-\->))\-\->" "")

      ;; Remove end of line
      (str/replace #"\r?\n|\r" " ")

      ;; Replace double quotes for single
      (str/replace #"\"" "'")

      ;; Remove the svg root tag
      (str/replace #"(?i)<svg.*?>" "")

      ;; And the closing tag
      (str/replace #"(?i)<\/svg>" "")

      ;; Remove some defs that can be redundant
      (str/replace #"<defs.*?/>" "")

      ;; Unifies the spaces into single space
      (str/replace #"\s+" " ")

      ;; Remove spaces at the beginning of the svg
      (str/replace #"^\s+" "")

      ;; Remove spaces at the end
      (str/replace #"\s+$" "")))

(defn encode-svg-cursor
  [id rotation x y height]
  (let [svg-path  (str cursor-folder "/" (name id) ".svg")
        data      (-> svg-path io/resource slurp parse-svg)
        data      (u/percent-encode data)

        data (if rotation
               (str/fmt "%3Cg transform='rotate(%s 8,8)'%3E%s%3C/g%3E" rotation data)
               data)]
    (str "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16' width='20px' "
         "height='" height "px' %3E" data "%3C/svg%3E\") " x " " y ", auto")))

(defmacro cursor-ref
  "Creates a static cursor given its name, rotation and x/y hotspot"
  ([id] (encode-svg-cursor id default-rotation default-hotspot-x default-hotspot-y default-height))
  ([id rotation] (encode-svg-cursor id rotation default-hotspot-x default-hotspot-y default-height))
  ([id rotation x y] (encode-svg-cursor id rotation x y default-height))
  ([id rotation x y height] (encode-svg-cursor id rotation x y height)))

(defmacro cursor-fn
  "Creates a dynamic cursor that can be rotated in runtime"
  [id initial]
  (let [cursor (encode-svg-cursor id "{{rotation}}" default-hotspot-x default-hotspot-y default-height)]
    `(fn [rot#]
       (str/replace ~cursor "{{rotation}}" (+ ~initial rot#)))))
