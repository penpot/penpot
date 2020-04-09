;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main
  (:require
   [cljs.spec.alpha :as s]
   [beicon.core :as rx]
   [goog.object :as gobj]
   [rumext.alpha :as mf]
   [uxbox.main.data.auth :refer [logout]]
   [uxbox.main.data.users :as udu]
   [uxbox.main.store :as st]
   [uxbox.main.ui :as ui]
   [uxbox.main.ui.modal :refer [modal]]
   [uxbox.main.ui.loader :refer [loader]]
   [uxbox.util.dom :as dom]
   [uxbox.util.html.history :as html-history]
   [uxbox.util.i18n :as i18n]
   [uxbox.util.theme :as theme]
   [uxbox.util.router :as rt]
   [uxbox.util.storage :refer [storage]]
   [uxbox.util.timers :as ts]))

(declare reinit)

(defn- on-navigate
  [router path]
  (let [match (rt/match router path)]
    (cond
      (and (= path "") (:auth storage))
      (st/emit! (rt/nav :dashboard-projects))

      (and (= path "") (not (:auth storage)))
      (st/emit! (rt/nav :login))

      (nil? match)
      (st/emit! (rt/nav :not-found))

      :else
      (st/emit! #(assoc % :route match)))))

(defn init-ui
  []
  (let [router (rt/init ui/routes)
        cpath (deref html-history/path)]

    (st/emit! #(assoc % :router router))
    (add-watch html-history/path ::main #(on-navigate router %4))

    (when (:profile storage)
      (st/emit! udu/fetch-profile))

    (mf/mount (mf/element ui/app) (dom/get-element "app"))
    (mf/mount (mf/element modal) (dom/get-element "modal"))
    (mf/mount (mf/element loader) (dom/get-element "loader"))

    (on-navigate router cpath)))

(def app-sym (.for js/Symbol "uxbox.app"))

(defn ^:export init
  []
  (let [translations (gobj/get goog.global "uxboxTranslations")
        themes (gobj/get goog.global "uxboxThemes")]
    (i18n/init! translations)
    (theme/init! themes)
    (unchecked-set js/window app-sym "main")
    (st/init)
    (init-ui)))

(defn reinit
  []
  (remove-watch html-history/path ::main)
  (mf/unmount (dom/get-element "app"))
  (mf/unmount (dom/get-element "modal"))
  (mf/unmount (dom/get-element "loader"))
  (init-ui))

(defn ^:dev/after-load after-load
  []
  (when (= "main" (unchecked-get js/window app-sym))
    (reinit)))

(defn ^:export toggle-debug
  []
  (swap! st/*debug* not))

