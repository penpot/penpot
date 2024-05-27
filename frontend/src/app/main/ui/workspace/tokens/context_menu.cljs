;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.context-menu
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.shape.radius :as ctsr]
   [app.main.data.events :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.shortcuts :as scd]
   [app.main.data.tokens :as dt]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.changes :as dch]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.context-menu :refer [menu-entry prevent-default]]
   [app.main.ui.workspace.tokens.core :as wtc]
   [app.util.dom :as dom]
   [app.util.timers :as timers]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def tokens-menu-ref
  (l/derived :token-context-menu refs/workspace-local))

(defn update-shape-radius-single-corner [value shape-ids attribute]
  (st/emit!
   (dch/update-shapes shape-ids
                      (fn [shape]
                        (when (ctsr/has-radius? shape)
                          (ctsr/set-radius-4 shape (first attribute) value)))
                      {:reg-objects? true
                       :attrs [:rx :ry :r1 :r2 :r3 :r4]})))

(defn apply-border-radius-token [token-id token-type-props attribute selected-shapes]
  (let [token (dt/get-token-data-from-token-id token-id)
        updated-token-type-props (if (#{:r1 :r2 :r3 :r4} attribute)
                                   (assoc token-type-props
                                          :on-update-shape update-shape-radius-single-corner
                                          :attributes #{attribute})
                                   token-type-props)]
    (wtc/on-apply-token {:token token
                         :token-type-props updated-token-type-props
                         :selected-shapes selected-shapes})))

(defn additional-actions [token-type token-id selected-shapes token-type-props]
  (case token-type
    :border-radius [{:title "All" :action #(apply-border-radius-token token-id token-type-props :all selected-shapes)}
                    {:title "Top Left" :action #(apply-border-radius-token token-id token-type-props :r1 selected-shapes)}
                    {:title "Top Right" :action #(apply-border-radius-token token-id token-type-props :r2 selected-shapes)}
                    {:title "Bottom Right" :action #(apply-border-radius-token token-id token-type-props :r3 selected-shapes)}
                    {:title "Bottom Left" :action #(apply-border-radius-token token-id token-type-props :r4 selected-shapes)}]
    []))

(defn generate-menu-entries [token-type-props token-id token-type selected-shapes]
  (let [{:keys [modal]} token-type-props
        default-actions [{:title "Delete Token" :action #(st/emit! (dt/delete-token token-id))}
                         {:title "Duplicate Token" :action #(st/emit! (dt/duplicate-token token-id))}
                         {:title "Edit Token" :action (fn [event]
                                                        (let [{:keys [key fields]} modal
                                                              token (dt/get-token-data-from-token-id token-id)]
                                                          (st/emit! dt/hide-token-context-menu)
                                                          (dom/stop-propagation event)
                                                          (modal/show! key {:x (.-clientX ^js event)
                                                                            :y (.-clientY ^js event)
                                                                            :position :right
                                                                            :fields fields
                                                                            :token token})))}]
        specific-actions (additional-actions token-type token-id selected-shapes token-type-props)
        all-actions (concat default-actions specific-actions)]
    all-actions))

(mf/defc token-pill-context-menu
  [{:keys [token-id token-type-props token-type selected-shapes]}]
  (let [menu-entries (generate-menu-entries token-type-props token-id token-type selected-shapes)]
    [:* (for [[index entry] (d/enumerate menu-entries)]
          [:& menu-entry {:title (:title entry) :on-click (:action entry)}])]))

(mf/defc token-context-menu
  []
  (let [mdata          (mf/deref tokens-menu-ref)
        top            (- (get-in mdata [:position :y]) 20)
        left           (get-in mdata [:position :x])
        dropdown-ref   (mf/use-ref)
        objects (mf/deref refs/workspace-page-objects)
        selected (mf/deref refs/selected-shapes)
        selected-shapes (into [] (keep (d/getf objects)) selected)]

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
                                      :token-type-props (:token-type-props mdata)
                                      :token-type (:token-type mdata)
                                      :selected-shapes selected-shapes}]])]]))