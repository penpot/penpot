;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.time
  (:require
   [vendor.datefns]
   [goog.object :as gobj]))

(def ^:private dateFns js/dateFns)
(def ^:private locales (gobj/get js/dateFns "locales"))

(defn now
  "Return the current Instant."
  []
  (js/Date.))

(defn format
  ([v fmt] (format v fmt nil))
  ([v fmt {:keys [locale]
           :or {locale "default"}}]
   (.format dateFns v fmt #js {:locale (gobj/get locales locale)})))

(defn timeago
  ([v] (timeago v nil))
  ([v {:keys [seconds? locale]
       :or {seconds? true
            locale "default"}}]
   (.formatDistanceToNow dateFns v
                         #js {:includeSeconds seconds?
                              :addSuffix true
                              :locale (gobj/get locales locale)})))
