(ns uxbox.schema
  (:refer-clojure :exclude [keyword uuid vector])
  (:require [bouncer.core :as b]
            [bouncer.validators :as v]
            [uxbox.shapes :refer (shape?)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Validators
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(v/defvalidator keyword
  "Validates maybe-an-int is a valid integer.
  For use with validation functions such as `validate` or `valid?`"
  {:default-message-format "%s must be a keyword"}
  [v]
  (cljs.core/keyword? v))

(v/defvalidator uuid
  "Validates maybe-an-int is a valid integer.
  For use with validation functions such as `validate` or `valid?`"
  {:default-message-format "%s must be a uuid instance"}
  [v]
  (instance? cljs.core.UUID v))

(v/defvalidator color
  "Validates if a string is a valid color."
  {:default-message-format "%s must be a valid hex color"}
  [v]
  (not (nil? (re-find #"^#[0-9A-Fa-f]{6}$" v))))

(v/defvalidator shape-type
  "Validates if a keyword is a shape type."
  {:default-message-format "%s must be a shape type keyword."}
  [v]
  (shape? v))

(v/defvalidator vector
  "Validats if `v` is vector."
  {:default-message-format "%s must be a vector instance."}
  [v]
  (vector? v))

(def required v/required)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn validate
  ([schema] #(validate schema %))
  ([schema data] (first (b/validate data schema))))

(defn validate!
  ([schema] #(validate! schema %))
  ([schema data]
   (when-let [errors (validate schema data)]
     (throw (ex-info "Invalid data" errors)))))
