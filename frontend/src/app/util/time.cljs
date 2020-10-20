;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2020 Andrey Antukh <niwi@niwi.nz>

(ns app.util.time
  (:require
   ["date-fns/format" :as df-format]
   ["date-fns/formatDistanceToNow" :as df-format-distance]
   ["date-fns/formatDistanceToNowStrict" :as df-format-distance-strict]
   ["date-fns/locale/fr" :as df-fr-locale]
   ["date-fns/locale/en-US" :as df-en-locale]
   ["date-fns/locale/es" :as df-es-locale]
   ["date-fns/locale/ru" :as df-ru-locale]
   [goog.object :as gobj]))

(def ^:private locales
  #js {:default df-en-locale
       :en df-en-locale
       :en_US df-en-locale
       :fr df-fr-locale
       :fr_FR df-fr-locale
       :es df-es-locale
       :es_ES df-es-locale
       :ru df-ru-locale
       :ru_RU df-ru-locale})

(defn now
  "Return the current Instant."
  []
  (js/Date.))

(defn format
  ([v fmt] (format v fmt nil))
  ([v fmt {:keys [locale]
           :or {locale "default"}}]
   (df-format v fmt #js {:locale (gobj/get locales locale)})))

(defn timeago
  ([v] (timeago v nil))
  ([v {:keys [seconds? locale]
       :or {seconds? true
            locale "default"}}]
   (when v
     (df-format-distance-strict v
                         #js {:includeSeconds seconds?
                              :addSuffix true
                              :locale (gobj/get locales locale)}))))
