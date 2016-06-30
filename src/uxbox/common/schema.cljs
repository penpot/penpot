;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.common.schema
  (:refer-clojure :exclude [keyword uuid vector boolean map set])
  (:require [struct.core :as st]
            [uxbox.common.i18n :refer (tr)]
            [uxbox.main.geom :refer (shape?)]))

;; (def datetime
;;   {:message "must be an instant"
;;    :optional true
;;    :validate #(instance? Instant %)})

(def required
  (assoc st/required :message "errors.form.required"))

(def string
  (assoc st/string :message "errors.form.string"))

(def number
  (assoc st/number :message "errors.form.number"))

(def integer
  (assoc st/integer :message "errors.form.integer"))

(def boolean
  (assoc st/boolean :message "errors.form.bool"))

(def identical-to
  (assoc st/identical-to :message "errors.form.identical-to"))

(def in-range st/in-range)
;; (def uuid-like st/uuid-like)
(def uuid st/uuid)
(def keyword st/keyword)
(def integer-str st/integer-str)
(def number-str st/number-str)
;; (def boolean-like st/boolean-like)
(def email st/email)
;; (def function st/function)
;; (def positive st/positive)
;; (def validate st/validate)
;; (def validate! st/validate!)

(def max-len
  {:message "errors.form.max-len"
   :optional true
   :validate (fn [v n]
               (let [len (count v)]
                 (>= len v)))})

(def min-len
  {:message "errors.form.min-len"
   :optional true
   :validate (fn [v n]
               (>= (count v) n))})

(def color
  {:message "errors.form.color"
   :optional true
   :validate #(not (nil? (re-find #"^#[0-9A-Fa-f]{6}$" %)))})

(def shape-type
  {:message "should be shape"
   :optional true
   :validate #(shape? %)})

(defn validate
  ([data schema]
   (validate data schema nil))
  ([data schema opts]
   (st/validate data schema opts)))

(defn validate!
  ([data schema]
   (validate! data schema nil))
  ([data schema opts]
   (let [[errors data] (validate data schema opts)]
     (if errors
       (throw (ex-info "Invalid data" errors))
       data))))

(defn valid?
  [data schema]
  (let [[errors data] (validate data schema)]
    (not errors)))
