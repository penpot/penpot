(ns app.main.ui.workspace.tokens.tinycolor
  "Bindings for tinycolor2 which supports a wide range of css compatible colors.

  Used by StyleDictionary, so we might as well use it directly."
  (:require
   ["tinycolor2" :as tinycolor]))

(defn tinycolor? [x]
  (and (instance? tinycolor x) (.isValid x)))

(defn valid-color [color-str]
  (let [tc (tinycolor color-str)]
    (when (.isValid tc) tc)))

(defn ->hex [tc]
  (assert (tinycolor? tc))
  (.toHex tc))

(defn color-format [tc]
  (assert (tinycolor? tc))
  (.getFormat tc))

(comment
  (some-> (valid-color "red") ->hex)
  (some-> (valid-color "red") color-format)
  nil)
