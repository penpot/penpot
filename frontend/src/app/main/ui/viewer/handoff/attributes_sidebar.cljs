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
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.main.ui.components.tab-container :refer [tab-container tab-element]]
   [app.main.ui.viewer.handoff.attrib-panel :refer [attrib-panel]]))

(defn make-selected-shapes-iref
  []
  (let [selected->shapes
        (fn [state]
          (let [selected (get-in state [:viewer-local :selected])
                objects (get-in state [:viewer-data :page :objects])
                resolve-shape #(get objects %)]
            (mapv resolve-shape selected)))]
    #(l/derived selected->shapes st/state)))

(mf/defc info-panel [{:keys [frame]}]
  (let [selected-ref (mf/use-memo (make-selected-shapes-iref))
        shapes (mf/deref selected-ref)]
    (if (> (count shapes) 1)
      ;; Multiple selection
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
                :text   [:layout :fill :typography :content :shadow :blur])]
          [:& attrib-panel {:frame frame
                            :shape shape
                            :options options}])))))

(mf/defc code-panel []
  [:div.element-options])

(mf/defc attributes-sidebar [{:keys [frame]}]
  (let [section (mf/use-state :info #_:code)]
    [:aside.settings-bar.settings-bar-right
     [:div.settings-bar-inside
      [:div.tool-window
       [:div.tool-window-bar.big
        [:span.tool-window-bar-icon i/text]
        [:span.tool-window-bar-title "Text"]]
       [:div.tool-window-content
        [:& tab-container {:on-change-tab #(reset! section %)
                           :selected @section}
         [:& tab-element {:id :info :title "Info"}
          [:& info-panel {:frame frame}]]

         [:& tab-element {:id :code :title "Code"}
          [:& code-panel]]]]]]]))
