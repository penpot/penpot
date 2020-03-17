;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2020 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2020 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.recent-files
  (:require
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.common.exceptions :as ex]
   [uxbox.main.constants :as c]
   [uxbox.main.data.dashboard :as dsh]
   [uxbox.main.store :as st]
   [uxbox.main.exports :as exports]
   [uxbox.main.refs :as refs]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.confirm :refer [confirm-dialog]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :as i18n :refer [t tr]]
   [uxbox.util.router :as rt]
   [uxbox.util.time :as dt]
   [uxbox.main.ui.dashboard.grid :refer [grid]])
  )

;; --- Component: Content

(def projects-ref
  (-> (l/key :projects)
      (l/derive st/state)))

(def recent-files-ref
  (-> (l/key :recent-files)
      (l/derive st/state)))

;; --- Component: Drafts Page

(mf/defc recent-project
  [{:keys [project files first? locale] :as props}]
  (let [project-id (:id project)]
    [:div.recent-files-row
     {:class-name (when first? "first")}
     [:div.recent-files-row-title
      [:h2.recent-files-row-title-name (:name project)]
      [:span.recent-files-row-title-info (str (:file-count project) " files")]
      (when files
        (let [time (-> (first files)
                       (:modified-at)
                       (dt/timeago {:locale locale}))]
          [:span.recent-files-row-title-info (str ", " time)]))]
     [:& grid {:id (:id project)
               :files (or files [])
               :hide-new? true}]]))


(mf/defc recent-files-page
  [{:keys [section team-id] :as props}]
  (mf/use-effect
   {:fn #(st/emit! (dsh/initialize-recent team-id))
    :deps (mf/deps team-id)})
  (let [projects (->> (mf/deref projects-ref)
                      (vals)
                      (filter #(pos? (:file-count %)))
                      (sort-by :modified-at)
                      (reverse))

        recent-files (mf/deref recent-files-ref)
        locale (i18n/use-locale)]
    (when (and projects recent-files)
      [:section.recent-files-page
       (for [project projects]
         [:& recent-project {:project project
                             :locale locale
                             :key (:id project)
                             :files (get recent-files (:id project))
                             :first? (= project (first projects))}])])))

