;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.viewer.handoff.right-sidebar
  (:require
   [rumext.alpha :as mf]
   [okulary.core :as l]
   [app.util.i18n :refer [t] :as i18n]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.main.ui.components.tab-container :refer [tab-container tab-element]]
   [app.main.ui.workspace.sidebar.layers :refer [element-icon]]
   [app.main.ui.viewer.handoff.attributes :refer [attributes]]
   [app.main.ui.viewer.handoff.code :refer [code]]))

(defn make-selected-shapes-iref
  []
  (let [selected->shapes
        (fn [state]
          (let [selected (get-in state [:viewer-local :selected])
                objects (get-in state [:viewer-data :page :objects])
                resolve-shape #(get objects %)]
            (mapv resolve-shape selected)))]
    #(l/derived selected->shapes st/state)))


(mf/defc right-sidebar
  [{:keys [frame]}]
  (let [locale (mf/deref i18n/locale)
        section (mf/use-state #_:info :code)
        selected-ref (mf/use-memo (make-selected-shapes-iref))
        shapes (mf/deref selected-ref)]
    [:aside.settings-bar.settings-bar-right
     [:div.settings-bar-inside
      (when (seq shapes)
        [:div.tool-window
         [:div.tool-window-bar.big
          (if (> (count shapes) 1)
            [:*
             [:span.tool-window-bar-icon i/layers]
             [:span.tool-window-bar-title (t locale "handoff.tabs.code.selected.multiple" (count shapes))]]
            [:*
             [:span.tool-window-bar-icon
              [:& element-icon {:shape (-> shapes first)}]]
             [:span.tool-window-bar-title (->> shapes first :type name (str "handoff.tabs.code.selected.") (t locale))]])
          ]
         [:div.tool-window-content
          [:& tab-container {:on-change-tab #(reset! section %)
                             :selected @section}
           [:& tab-element {:id :info :title (t locale "handoff.tabs.info")}
            [:& attributes {:frame frame
                            :shapes shapes}]]

           [:& tab-element {:id :code :title (t locale "handoff.tabs.code")}
            [:& code {:frame frame
                      :shapes shapes}]]]]])]]))
