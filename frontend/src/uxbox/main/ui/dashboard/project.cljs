;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2020 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.project
  (:require
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.main.data.dashboard :as dsh]
   [uxbox.main.store :as st]
   [uxbox.main.ui.dashboard.grid :refer [grid]]))

(def files-ref
  (-> (comp (l/key :files)
            (l/lens vals))
      (l/derive st/state)))

(mf/defc project-page
  [{:keys [section team-id project-id] :as props}]
  (let [files (->> (mf/deref files-ref)
                   (sort-by :modified-at)
                   (reverse))]
    (mf/use-effect
     {:fn #(st/emit! (dsh/initialize-project team-id project-id))
      :deps (mf/deps team-id project-id)})

    [:section.projects-page
     [:& grid { :id project-id :files files }]]))

