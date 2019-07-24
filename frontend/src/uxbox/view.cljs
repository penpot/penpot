;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>

(ns ^:figwheel-hooks uxbox.view
  (:require
   [rumext.core :as mx]
   [rumext.alpha :as mf]
   [uxbox.util.dom :as dom]
   [uxbox.util.html.history :as html-history]
   [uxbox.util.i18n :as i18n :refer [tr]]
   [uxbox.util.router :as rt]
   [uxbox.view.locales.en :as en]
   [uxbox.view.locales.fr :as fr]
   [uxbox.view.store :as st]
   [uxbox.view.ui :as ui]
   [uxbox.view.ui.lightbox :refer [lightbox]]
   [uxbox.view.ui.loader :refer [loader]]))

(i18n/update-locales! (fn [locales]
                        (-> locales
                            (assoc "en" en/locales)
                            (assoc "fr" fr/locales))))

(declare reinit)

(i18n/on-locale-change!
 (fn [new old]
   (println "Locale changed from" old " to " new)
   (reinit)))

(defn- on-navigate
  [router path]
  (let [match (rt/match router path)]
    (prn "view$on-navigate" path)
    (cond
      (nil? match)
      (prn "TODO 404 view" match)

      :else
      (st/emit! #(assoc % :route match)))))

(defn init-ui
  []
  (let [router (rt/init ui/routes)
        cpath (deref html-history/path)]

    (st/emit! #(assoc % :router router))
    (add-watch html-history/path ::view #(on-navigate router %4))

    (mf/mount (mf/element ui/app) (dom/get-element "app"))
    (mf/mount (lightbox) (dom/get-element "lightbox"))
    (mf/mount (loader) (dom/get-element "loader"))

    (on-navigate router cpath)))

(def app-sym (.for js/Symbol "uxbox.app"))

(defn ^:export init
  []
  (unchecked-set js/window app-sym "view")
  (st/init)
  (init-ui))

(defn reinit
  []
  (remove-watch html-history/path ::view)
  (mf/unmount (dom/get-element "app"))
  (mf/unmount (dom/get-element "lightbox"))
  (mf/unmount (dom/get-element "loader"))
  (init-ui))

(defn ^:after-load after-load
  []
  (when (= "view" (unchecked-get js/window app-sym))
    (reinit)))


