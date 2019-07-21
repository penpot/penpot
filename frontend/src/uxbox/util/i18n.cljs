;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.i18n
  "A i18n foundation."
  (:require [cuerdas.core :as str]
            [uxbox.util.storage :refer (storage)]))

(defonce locale (atom (get storage ::locale :en)))
(defonce state (atom {}))

(defn update-locales!
  [callback]
  (swap! state callback))

(defn set-current-locale!
  [v]
  (swap! storage assoc ::locale v)
  (reset! locale v))

(defn on-locale-change!
  [callback]
  (add-watch locale ::main (fn [_ _ old-locale new-locale]
                             (when (not= old-locale new-locale)
                               (callback new-locale old-locale)))))

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
         locale  (deref locale)
         value  (or (get-in @state [locale t])
                    default)]
     (if (vector? value)
       (or (second value) default)
       value)))
  ([t & args]
   (let [locale (deref locale)
         value (get-in @state [locale t] (name t))
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
