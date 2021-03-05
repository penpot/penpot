;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2021 Andrey Antukh <niwi@niwi.nz>

(ns app.util.time
  (:require
   ["date-fns/parseISO" :as dateFnsParseISO]
   ["date-fns/formatISO" :as dateFnsFormatISO]
   ["date-fns/format" :as dateFnsFormat]
   ["date-fns/formatDistanceToNowStrict" :as dateFnsFormatDistanceToNowStrict]
   ["date-fns/locale/fr" :as dateFnsLocalesFr]
   ["date-fns/locale/en-US" :as dateFnsLocalesEnUs]
   ["date-fns/locale/zh-CN" :as dateFnsLocalesZhCn]
   ["date-fns/locale/es" :as dateFnsLocalesEs]
   ["date-fns/locale/ru" :as dateFnsLocalesRu]
   [app.util.object :as obj]))

(def ^:private locales
  #js {:en dateFnsLocalesEnUs
       :fr dateFnsLocalesFr
       :es dateFnsLocalesEs
       :ru dateFnsLocalesRu
       :zh_cn dateFnsLocalesZhCn})

(defn now
  "Return the current Instant."
  []
  (js/Date.))

(defn parse
  [v]
  (^js dateFnsParseISO v))

(defn format-iso
  [v]
  (^js dateFnsFormatISO v))

(defn format
  ([v fmt] (format v fmt nil))
  ([v fmt {:keys [locale] :or {locale "en"}}]
   (dateFnsFormat v fmt #js {:locale (obj/get locales locale)})))

(defn timeago
  ([v] (timeago v nil))
  ([v {:keys [locale] :or {locale "en"}}]
   (when v
     (->> #js {:includeSeconds true
               :addSuffix true
               :locale (obj/get locales locale)}
          (dateFnsFormatDistanceToNowStrict v)))))
