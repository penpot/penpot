(ns uxbox.schema
  (:refer-clojure :exclude [keyword uuid])
  (:require [bouncer.core :as b]
            [bouncer.validators :as v]))

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

