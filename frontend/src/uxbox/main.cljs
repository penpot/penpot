;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns ^:figwheel-hooks uxbox.main
  (:require
   [rumext.alpha :as mf]
   [uxbox.main.data.auth :refer [logout]]
   [uxbox.main.data.users :as udu]
   [uxbox.main.locales.en :as en]
   [uxbox.main.locales.fr :as fr]
   [uxbox.main.store :as st]
   [uxbox.main.ui :as ui]
   [uxbox.main.ui.lightbox :refer [lightbox]]
   [uxbox.main.ui.loader :refer [loader]]
   [uxbox.util.dom :as dom]
   [uxbox.util.html.history :as html-history]
   [uxbox.util.i18n :as i18n :refer [tr]]
   [uxbox.util.messages :as uum]
   [uxbox.util.router :as rt]
   [uxbox.util.storage :refer [storage]]
   [uxbox.util.timers :as ts]))

;; --- i18n

(declare reinit)

(i18n/update-locales! (fn [locales]
                        (-> locales
                            (assoc "en" en/locales)
                            (assoc "fr" fr/locales))))

(i18n/on-locale-change!
 (fn [new old]
   (println "Locale changed from" old " to " new)
   (reinit)))

;; --- Error Handling

(defn- on-navigate
  [router path]
  (let [match (rt/match router path)]
    (prn "main$on-navigate" path)

    (cond
      (and (= path "") (:auth storage))
      (st/emit! (rt/nav :dashboard/projects))

      (and (= path "") (not (:auth storage)))
      (st/emit! (rt/nav :auth/login))

      (nil? match)
      (prn "TODO 404 main")

      :else
      (st/emit! #(assoc % :route match)))))

(defn init-ui
  []
  (let [router (rt/init ui/routes)
        cpath (deref html-history/path)]

    (st/emit! #(assoc % :router router))
    (add-watch html-history/path ::main #(on-navigate router %4))

    (when (:auth storage)
      (st/emit! (udu/fetch-profile)))

    (mf/mount (ui/app) (dom/get-element "app"))
    (mf/mount (lightbox) (dom/get-element "lightbox"))
    (mf/mount (loader) (dom/get-element "loader"))

    (on-navigate router cpath)))

(def app-sym (.for js/Symbol "uxbox.app"))

(defn ^:export init
  []
  (unchecked-set js/window app-sym "main")
  (st/init)
  (init-ui))

(defn reinit
  []
  (remove-watch html-history/path ::main)
  (mf/unmount (dom/get-element "app"))
  (mf/unmount (dom/get-element "lightbox"))
  (mf/unmount (dom/get-element "loader"))
  (init-ui))

(defn ^:after-load after-load
  []
  (when (= "main" (unchecked-get js/window app-sym))
    (reinit)))
