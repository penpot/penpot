;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.i18n
  "A i18n foundation."
  (:require
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [beicon.core :as rx]
   [goog.object :as gobj]
   [uxbox.config :as cfg]
   [uxbox.util.transit :as t]
   [uxbox.util.storage :refer [storage]]))

(defonce locale (get storage ::locale cfg/default-lang))
(defonce locale-sub (rx/subject))
(defonce translations #js {})

;; The traslations `data` is a javascript object and should be treated
;; with `goog.object` namespace functions instead of a standart
;; clojure functions. This is for performance reasons because this
;; code is executed in the critical part (application bootstrap) and
;; used in many parts of the application.

(defn init!
  [data]
  (set! translations data))

(defn set-current-locale!
  [v]
  (swap! storage assoc ::locale v)
  (set! locale v)
  (rx/push! locale-sub v))

(defn set-default-locale!
  []
  (set-current-locale! cfg/default-lang))

(deftype C [val]
  IDeref
  (-deref [o] val))

(defn ^boolean c?
  [r]
  (instance? C r))

;; A main public api for translate strings.

;; A marker type that is used just for mark
;; a parameter that reprsentes the counter.

(defn c
  [x]
  (C. x))

(defn t
  ([locale code]
   (let [code (name code)
         value (gobj/getValueByKeys translations code locale)
         value (if (nil? value) code value)]
     (if (array? value)
       (aget value 0)
       value)))
  ([locale code & args]
   (let [code (name code)
         value (gobj/getValueByKeys translations code locale)
         value (if (nil? value) code value)
         plural (first (filter c? args))
         value (if (array? value)
                 (if (= @plural 1) (aget value 0) (aget value 1))
                 value)]
     (apply str/format value (map #(if (c? %) @% %) args)))))

(defn tr
  ([code] (t locale code))
  ([code & args] (apply t locale code args)))

(defn use-locale
  []
  (let [[locale set-locale] (mf/useState locale)]
    (mf/useEffect (fn []
                    (let [sub (rx/sub! locale-sub #(set-locale %))]
                      #(rx/dispose! sub)))
                  #js [])
    locale))

