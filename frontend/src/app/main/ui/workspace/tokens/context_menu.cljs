;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.context-menu
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.types.shape.radius :as ctsr]
   [app.main.data.modal :as modal]
   [app.main.data.tokens :as dt]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.workspace.context-menu :refer [menu-entry prevent-default]]
   [app.main.ui.workspace.tokens.core :as wtc]
   [app.util.dom :as dom]
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

(defn apply-border-radius-token [{:keys [token-id token-type-props selected-shapes]} attribute]
  (let [token (dt/get-token-data-from-token-id token-id)
        updated-token-type-props (if (#{:r1 :r2 :r3 :r4} attribute)
                                   (assoc token-type-props
                                          :on-update-shape update-shape-radius-single-corner
                                          :attributes #{attribute})
                                   token-type-props)]
    (wtc/on-apply-token {:token token
                         :token-type-props updated-token-type-props
                         :selected-shapes selected-shapes})))

(defn update-layout-spacing [value shape-ids attributes]
  (if-let [layout-gap (cond
                        (:row-gap attributes) {:row-gap value}
                        (:column-gap attributes) {:column-gap value})]
    (st/emit! (dwsl/update-layout shape-ids {:layout-gap layout-gap}))
    (st/emit! (dwsl/update-layout shape-ids {:layout-padding (zipmap attributes (repeat value))}))))


(defn apply-spacing-token [{:keys [token-id token-type-props selected-shapes]} attribute]
  (let [token (dt/get-token-data-from-token-id token-id)
        attribute (set attribute)
        updated-token-type-props (assoc token-type-props
                                        :on-update-shape update-layout-spacing
                                        :attributes attribute)]
    (wtc/on-apply-token {:token token
                         :token-type-props updated-token-type-props
                         :selected-shapes selected-shapes})))

(defn update-shape-dimensions [value shape-ids attributes]
  (st/emit! (dwt/update-dimensions shape-ids (first attributes) value)))

(defn update-layout-sizing-limits [value shape-ids attributes]
  (st/emit! (dwsl/update-layout-child shape-ids {(first attributes) value})))

(defn apply-sizing-token [{:keys [token-id token-type-props selected-shapes]} attribute]
  (let [token (dt/get-token-data-from-token-id token-id)
        updated-token-type-props (cond
                                   (#{:width :height} attribute)
                                   (assoc token-type-props
                                          :on-update-shape update-shape-dimensions
                                          :attributes #{attribute})

                                   (#{:layout-item-min-w :layout-item-max-w
                                      :layout-item-min-h :layout-item-max-h} attribute)
                                   (assoc token-type-props
                                          :on-update-shape update-layout-sizing-limits
                                          :attributes #{attribute}))]
    (wtc/on-apply-token {:token token
                         :token-type-props updated-token-type-props
                         :selected-shapes selected-shapes})))


(defn additional-actions [{:keys [token-type] :as context-data}]
  (case token-type
    :border-radius (let [action #(apply-border-radius-token context-data %)]
                     [{:title "All" :action #(action :all)}
                      {:title "Top Left" :action #(action :r1)}
                      {:title "Top Right" :action #(action :r2)}
                      {:title "Bottom Right" :action #(action :r3)}
                      {:title "Bottom Left" :action #(action :r4)}])
    :spacing       (let [action #(apply-spacing-token context-data %)]
                     [{:title "All" :action #(action #{:p1 :p2 :p3 :p4})}
                      {:title "Column Gap" :action #(action #{:column-gap})}
                      {:title "Vertical padding" :action #(action #{:p1 :p3})}
                      {:title "Horizontal padding" :action #(action #{:p2 :p4})}
                      {:title "Row Gap" :action #(action #{:row-gap})}
                      {:title "Top" :action #(action #{:p1})}
                      {:title "Right" :action #(action #{:p2})}
                      {:title "Bottom" :action #(action #{:p3})}
                      {:title "Left" :action #(action #{:p4})}])

    :sizing       (let [action #(apply-sizing-token context-data %)]
                    [{:title "All" :action #(action :all)}
                     {:title "Width" :action #(action :width)}
                     {:title "Height" :action #(action :height)}
                     {:title "Min width" :action #(action :layout-item-min-w)}
                     {:title "Max width" :action #(action :layout-item-max-w)}
                     {:title "Min height" :action #(action :layout-item-min-h)}
                     {:title "Max height" :action #(action :layout-item-max-h)}])

    []))

(defn generate-menu-entries [{:keys [token-id token-type-props token-type selected-shapes] :as context-data}]
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
        specific-actions (additional-actions context-data)
        all-actions (concat specific-actions default-actions)]
    all-actions))

(mf/defc token-pill-context-menu
  [{:keys [token-id token-type-props token-type selected-shapes] :as context-data}]
  (let [menu-entries (generate-menu-entries context-data)]
    (for [[index entry] (d/enumerate menu-entries)]
      [:& menu-entry {:title (:title entry) :on-click (:action entry) :key index}])))

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