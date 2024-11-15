(ns app.main.ui.workspace.tokens.tinycolor
  "Bindings for tinycolor2 which supports a wide range of css compatible colors.

  This library was chosen as it is already used by StyleDictionary,
  so there is no extra dependency cost and there was no clojure alternatives with all the necessary features."
  (:require
   ["tinycolor2$default" :as tinycolor]))

(defn tinycolor? [^js x]
  (and (instance? tinycolor x) (.isValid x)))

(defn valid-color [color-str]
  (let [tc (tinycolor color-str)]
    (when (.isValid tc) tc)))

(defn ->hex [^js tc]
  (assert (tinycolor? tc))
  (.toHex tc))

(defn color-format [^js tc]
  (assert (tinycolor? tc))
  (.getFormat tc))

(comment
  (some-> (valid-color "red") ->hex)
  (some-> (valid-color "red") color-format)
  nil)
