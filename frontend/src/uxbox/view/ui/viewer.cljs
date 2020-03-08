;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.view.ui.viewer
  (:require
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.util.i18n :refer [tr]]
   [uxbox.util.data :refer [seek]]
   [uxbox.view.data.viewer :as dv]
   [uxbox.view.store :as st]
   [uxbox.view.ui.viewer.frame :refer [frame]]
   [uxbox.view.ui.viewer.nav :refer [nav]]
   [uxbox.view.ui.viewer.sitemap :refer [sitemap]]
   [lentes.core :as l]))

;; --- Refs

(defn- sort-pages
  [{:keys [pages] :as state}]
  (let [get-order #(get-in % [:metadata :order])]
    (assoc state :pages (->> (sort-by get-order pages)
                             (into [])))))

(def state-ref
  (-> (comp (l/select-keys [:flags :pages :project])
            (l/lens sort-pages))
      (l/derive st/state)))

;; --- Component

(mf/defc viewer-page
  [{:keys [token id]}]
  (let [{:keys [project pages flags]} (mf/deref state-ref)]
    (mf/use-effect
     {:fn #(st/emit! (dv/initialize token))})
    (when (seq pages)
      [:section.view-content
       (when (contains? flags :sitemap)
         [:& sitemap {:project project
                      :pages pages
                      :selected id}])
       [:& nav {:flags flags}]
       [:& frame {:page (seek #(= id (:id %)) pages)}]])))
