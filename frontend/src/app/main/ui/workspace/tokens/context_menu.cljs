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
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.workspace.context-menu :refer [menu-entry prevent-default]]
   [app.main.ui.workspace.tokens.core :as wtc]
   [app.util.dom :as dom]
   [clojure.set :as set]
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

(defn apply-border-radius-token [{:keys [token-id token-type-props selected-shapes]} attributes]
  (let [token (dt/get-token-data-from-token-id token-id)
        updated-token-type-props (if (set/superset? #{:r1 :r2 :r3 :r4} attributes)
                                   (assoc token-type-props
                                          :on-update-shape update-shape-radius-single-corner
                                          :attributes attributes)
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


(defn additional-actions [{:keys [token-type] :as context-data}]
  (let [attributes->actions (fn [update-fn coll]
                              (for [{:keys [attributes] :as item} coll]
                                (assoc item :action #(update-fn context-data attributes))))]
    (case token-type
      :border-radius (attributes->actions
                      apply-border-radius-token
                      [{:title "All" :attributes #{:all}}
                       {:title "Top Left" :attributes #{:r1}}
                       {:title "Top Right" :attributes #{:r2}}
                       {:title "Bottom Right" :attributes #{:r3}}
                       {:title "Bottom Left" :attributes #{:r4}}])
      :spacing       (attributes->actions
                      apply-spacing-token
                      [{:title "All" :attributes #{:p1 :p2 :p3 :p4}}
                       {:title "Column Gap" :attributes #{:column-gap}}
                       {:title "Vertical padding" :attributes #{:p1 :p3}}
                       {:title "Horizontal padding" :attributes #{:p2 :p4}}
                       {:title "Row Gap" :attributes #{:row-gap}}
                       {:title "Top" :attributes #{:p1}}
                       {:title "Right" :attributes #{:p2}}
                       {:title "Bottom" :attributes #{:p3}}
                       {:title "Left" :attributes #{:p4}}])
      [])))

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
  [context-data]
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
