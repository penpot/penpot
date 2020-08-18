;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.dashboard.libraries
  (:require
   [okulary.core :as l]
   [rumext.alpha :as mf]
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.dom :as dom]
   [app.util.router :as rt]
   [app.main.data.dashboard :as dsh]
   [app.main.store :as st]
   [app.main.ui.modal :as modal]
   [app.main.ui.keyboard :as kbd]
   [app.main.ui.confirm :refer [confirm-dialog]]
   [app.main.ui.components.context-menu :refer [context-menu]]
   [app.main.ui.dashboard.grid :refer [grid]]))

(def files-ref
  (-> (comp vals :files)
      (l/derived st/state)))

(mf/defc libraries-page
  [{:keys [section team-id] :as props}]
  (let [files (->> (mf/deref files-ref)
                   (sort-by :modified-at)
                   (reverse))]
    (mf/use-effect
     (mf/deps section team-id)
     #(st/emit! (dsh/initialize-libraries team-id)))

    [:*
      [:header.main-bar
       [:h1.dashboard-title (tr "dashboard.header.libraries")]]
      [:section.libraries-page
       [:& grid {:files files :hide-new? true}]]]))

