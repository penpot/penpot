;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.dashboard.libraries
  (:require
   [okulary.core :as l]
   [rumext.alpha :as mf]
   [uxbox.main.ui.icons :as i]
   [uxbox.util.i18n :as i18n :refer [tr]]
   [uxbox.util.dom :as dom]
   [uxbox.util.router :as rt]
   [uxbox.main.data.dashboard :as dsh]
   [uxbox.main.store :as st]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.confirm :refer [confirm-dialog]]
   [uxbox.main.ui.components.context-menu :refer [context-menu]]
   [uxbox.main.ui.dashboard.grid :refer [grid]]))

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

