;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.cursors
  (:import java.net.URLEncoder)
  (:require [rumext.alpha]
            [clojure.java.io :as io] 
            [lambdaisland.uri.normalize :as uri]
            [cuerdas.core :as str]))

(def cursor-folder "images/cursors")

(def default-hotspot-x 12)
(def default-hotspot-y 12)
(def default-rotation 0)

(defn parse-svg [svg-data]
  (-> svg-data
      ;; Remove the <?xml ?> header
      (str/replace #"(?i)<\?xml[^\?]*\?>", "")

      ;; Remove comments
      (str/replace #"<\!\-\-(.*?(?=\-\->))\-\->" "")

      ;; Remofe end of line
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
  [id rotation x y]
  (let [svg-path (str cursor-folder "/" (name id) ".svg")
        data (-> svg-path io/resource slurp parse-svg uri/percent-encode)
        transform (if rotation (str " transform='rotate(" rotation ")'") "")
        data (clojure.pprint/cl-format
              nil
              "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16' width='20px' height='20px'~A%3E~A%3C/svg%3E\") ~A ~A, auto"
              transform data x y)]
    data))

(defmacro cursor-ref
  "Creates a static cursor given its name, rotation and x/y hotspot"
  ([id] (encode-svg-cursor id default-rotation default-hotspot-x default-hotspot-y))
  ([id rotation] (encode-svg-cursor id rotation default-hotspot-x default-hotspot-y))
  ([id rotation x y] (encode-svg-cursor id rotation x y)))

(defmacro cursor-fn
  "Creates a dynamic cursor that can be rotated in runtime"
  [id initial]
  (let [cursor (encode-svg-cursor id "{{rotation}}" default-hotspot-x default-hotspot-y)]
    `(fn [rot#]
       (str/replace ~cursor "{{rotation}}" (+ ~initial rot#)))))
