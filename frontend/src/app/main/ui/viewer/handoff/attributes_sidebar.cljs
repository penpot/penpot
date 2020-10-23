;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.viewer.handoff.attributes-sidebar
  (:require
   [rumext.alpha :as mf]
   [okulary.core :as l]
   [app.util.i18n :refer [t] :as i18n]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.main.ui.components.tab-container :refer [tab-container tab-element]]
   [app.main.ui.viewer.handoff.attrib-panel :refer [attrib-panel]]
   [app.main.ui.workspace.sidebar.layers :refer [element-icon]]))

(defn make-selected-shapes-iref
  []
  (let [selected->shapes
        (fn [state]
          (let [selected (get-in state [:viewer-local :selected])
                objects (get-in state [:viewer-data :page :objects])
                resolve-shape #(get objects %)]
            (mapv resolve-shape selected)))]
    #(l/derived selected->shapes st/state)))

(mf/defc info-panel [{:keys [frame shapes]}]
  (if (> (count shapes) 1)
    ;; TODO:Multiple selection
    nil
    ;; Single shape
    (when-let [shape (first shapes)]
      (let [options
            (case (:type shape)
              :frame  [:layout :fill]
              :group  [:layout]
              :rect   [:layout :fill :stroke :shadow :blur]
              :circle [:layout :fill :stroke :shadow :blur]
              :path   [:layout :fill :stroke :shadow :blur]
              :curve  [:layout :fill :stroke :shadow :blur]
              :image  [:image :layout :shadow :blur]
              :text   [:layout :typography :shadow :blur])]
        [:& attrib-panel {:frame frame
                          :shape shape
                          :options options}]))))

(mf/defc code-panel []
  [:div.element-options])

(mf/defc attributes-sidebar [{:keys [frame]}]
  (let [locale (mf/deref i18n/locale)
        section (mf/use-state :info #_:code)
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
            [:& info-panel {:frame frame
                            :shapes shapes}]]

           [:& tab-element {:id :code :title (t locale "handoff.tabs.code")}
            [:& code-panel]]]]])]]))
