;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.history
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [app.main.ui.icons :as i]
   [app.main.data.history :as udh]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.util.data :refer [read-string]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [t] :as i18n]
   [app.util.router :as r]
   [app.util.time :as dt]
   [okulary.core :as l]
   [app.main.store :as st]))

(def workspace-undo
  (l/derived :workspace-undo st/state))

(mf/defc history-toolbox []
  (let [locale (mf/deref i18n/locale)
        {:keys [items index]} (mf/deref workspace-undo)
        objects (mf/deref refs/workspace-page-objects)]
    [:div.history-toolbox
     [:div.history-toolbox-title "History"]
     (when (> (count items) 0)
       [:ul.undo-history
        [:*
         (when (or (nil? index) (>= index (count items))) [:hr.separator])
         (for [[idx-entry {:keys [redo-changes]}] (->> items (map-indexed vector) reverse)]
           [:*
            (when (= index idx-entry) [:hr.separator {:data-index index}])
            [:li.undo-entry {:key (str "entry-" idx-entry)}
             (for [[idx-change {:keys [type id operations]}] (map-indexed vector redo-changes)]
               [:div.undo-entry-change
                [:div.undo-entry-change-data (when type (str type)) " " (when id (str (get-in objects [id :name] (subs (str id) 0 8))))]
                (when operations
                  [:div.undo-entry-change-data (str/join ", " (map (comp name :attr) operations))])])]])
         (when (= index -1) [:hr.separator])]])]))

