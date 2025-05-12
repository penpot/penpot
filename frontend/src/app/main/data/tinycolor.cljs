;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.tinycolor
  "Bindings for tinycolor2 which supports a wide range of css compatible colors.

  This library was chosen as it is already used by StyleDictionary,
  so there is no extra dependency cost and there was no clojure alternatives with all the necessary features."
  (:require
   ["tinycolor2$default" :as tinycolor]
   [cuerdas.core :as str]))

(defn tinycolor? [^js x]
  (and (instance? tinycolor x) (.isValid x)))

(defn hex? [^js tc]
  (str/starts-with? (.getFormat tc) "hex"))

(defn valid-color
  "Checks if `color-str` is a valid css color."
  [color-str]
  (let [tc (tinycolor color-str)]
    (when (and (.isValid tc)
               ;; Invalid CSS color strings will return `false` for `.getFormat`
               (.getFormat tc)
               ;; Values like `1111` will still return `hex8` as format which are non-valid css colors,
               ;; so we reject hex values without a # prefix
               (if (hex? tc)
                 (str/starts-with? (.getOriginalInput tc) "#")
                 true))
      tc)))

(defn hex-without-hash-prefix? [color-str]
  (when (not= "#" (first color-str))
    (let [tc (tinycolor color-str)]
      (str/starts-with? (.getFormat tc) "hex"))))

(defn ->string
  "Stringify `tc` to `format`, uses `hex` as per default."
  [^js tc format]
  (let [format' (case format
                  ;; Tinycolor `.toString` doesnt support the `a` suffix it gives you via `.getFormat`
                  "rgba" "rgb"
                  "hsva" "hsv"
                  ;; Keep these formats
                  "rgb"  "rgb"
                  "hsv"  "hsv"
                  ;; Fall back to hex as the default
                  "hex")]
    (.toString tc format')))

(defn ->hex-string [^js tc]
  (assert (tinycolor? tc))
  (.toHexString tc))

(defn color-format [^js tc]
  (assert (tinycolor? tc))
  (.getFormat tc))

(defn alpha [^js tc]
  (assert (tinycolor? tc))
  (.getAlpha tc))

(defn set-alpha [^js tc alpha]
  (assert (tinycolor? tc))
  (.setAlpha tc alpha))
