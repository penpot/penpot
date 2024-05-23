;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.context-menu
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.data.modal :as modal]
   [app.common.data.macros :as dm]
   [app.main.data.events :as ev]
   [app.main.data.shortcuts :as scd]
   [app.main.data.tokens :as dt]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.context-menu :refer [menu-entry prevent-default]]
   [app.util.dom :as dom]
   [app.util.timers :as timers]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def tokens-menu-ref
  (l/derived :token-context-menu refs/workspace-local))

(mf/defc token-pill-context-menu
  [{:keys [token-id token-type-props]}]
  (let [{:keys [modal attributes title]} token-type-props
        do-delete #(st/emit! (dt/delete-token token-id))
        do-duplicate #(st/emit! (dt/duplicate-token token-id))
        do-edit #(println "Editing " modal)
        edit-token (mf/use-fn
                    (fn [event]
                      (let [{:keys [key fields]} modal
                            token (dt/get-token-data-from-token-id token-id)]
                        (dom/stop-propagation event)
                        (modal/show! key {:x (.-clientX ^js event)
                                          :y (.-clientY ^js event)
                                          :position :right
                                          :fields fields
                                          :token-type type
                                          :token-name (:name token)
                                          :token-value (:value token)
                                          :token-description (:description token)}))))]
    [:*
     [:& menu-entry {:title "Delete Token" :on-click do-delete}]
     [:& menu-entry {:title "Duplicate Token" :on-click do-duplicate}]
     [:& menu-entry {:title "Edit Token" :on-click edit-token}]]))

(mf/defc token-context-menu
  []
  (let [mdata          (mf/deref tokens-menu-ref)
        top            (- (get-in mdata [:position :y]) 20)
        left           (get-in mdata [:position :x])
        dropdown-ref   (mf/use-ref)]

    (mf/use-effect
     (mf/deps mdata)
     #(let [dropdown (mf/ref-val dropdown-ref)]
        (when dropdown
          (let [bounding-rect (dom/get-bounding-rect dropdown)
                window-size (dom/get-window-size)
                delta-x (max (- (+ (:right bounding-rect) 250) (:width window-size)) 0)
                delta-y (max (- (:bottom bounding-rect) (:height window-size)) 0)
                new-style (str "top: " (- top delta-y) "px; "
                               "left: " (- left delta-x) "px;")]
            (when (or (> delta-x 0) (> delta-y 0))
              (.setAttribute ^js dropdown "style" new-style))))))

    [:& dropdown {:show (boolean mdata)
                  :on-close #(st/emit! dt/hide-token-context-menu)}
     [:div {:class (stl/css :token-context-menu)
            :ref dropdown-ref
            :style {:top top :left left}
            :on-context-menu prevent-default}
      (when  (= :token (:type mdata))
        [:ul {:class (stl/css :context-list)}
         [:& token-pill-context-menu {:token-id (:token-id mdata)
                                      :token-type-props (:token-type-props mdata)}]])]]))