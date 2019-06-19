;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.view
  (:require [uxbox.config]
            [uxbox.view.store :as st]
            [uxbox.view.ui :as ui]
            [uxbox.main.locales.en :as en]
            [uxbox.main.locales.fr :as fr]
            [uxbox.util.i18n :as i18n]))

(i18n/update-locales! (fn [locales]
                        (-> locales
                            (assoc :en en/locales)
                            (assoc :fr fr/locales))))

(i18n/on-locale-change!
 (fn [new old]
   (println "Locale changed from" old " to " new)
   (ui/init)))

(defn ^:export init
  []
  (st/init)
  (ui/init-routes)
  (ui/init))
