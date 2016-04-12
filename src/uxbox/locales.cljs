;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.locales
  "A i18n foundation."
  (:require [hodgepodge.core :refer [local-storage]]
            [cuerdas.core :as str]
            [uxbox.locales.en :as locales-en]))

(defonce +locales+
  {:en locales-en/+locales+})

(defonce +locale+
  (get local-storage ::locale :en))

;; A marker type that is used just for mark
;; a parameter that reprsentes the counter.

(deftype C [val]
  IDeref
  (-deref [o] val))

(defn c
  [x]
  (C. x))

(defn ^boolean c?
  [r]
  (instance? C r))

;; A main public api for translate strings.

(defn tr
  "Translate the string."
  ([t]
   (let [default (name t)
         value (get-in +locales+ [+locale+ t] default)]
     (if (vector? value)
       (or (second value) default)
       value)))
  ([t & args]
   (let [value (get-in +locales+ [+locale+ t] (name t))
         plural (first (filter c? args))
         args (mapv #(if (c? %) @% %) args)
         value (cond
                 (and (vector? value)
                      (= 3 (count value)))
                 (nth value (min 2 @plural))

                 (vector? value)
                 (if (= @plural 1) (first value) (second value))

                 :else
                 value)]
     (apply str/format value args))))
