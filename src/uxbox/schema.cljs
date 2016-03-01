;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.schema
  (:refer-clojure :exclude [keyword uuid vector boolean])
  (:require [bouncer.core :as b]
            [bouncer.validators :as v]
            [cuerdas.core :as str]
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

(v/defvalidator function
  "Validats if `v` is function."
  {:default-message-format "%s must be a function."}
  [v]
  (fn? v))

(def ^:const +email-re+
  #"^[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+$")

(v/defvalidator email
  "Validate if `v` is a valid email."
  {:default-message-format "% must be a valid email."}
  [v]
  (clojure.core/boolean (re-seq +email-re+ v)))

(def required v/required)
(def number v/number)
(def integer v/integer)
(def boolean v/boolean)
(def string v/string)

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

(defn valid?
  [validator data]
  (let [result (validator data)]
    (if result
      result
      (let [message (:default-message-format (meta validator))
            message (str/format message data)]
        (throw (ex-info message {}))))))

