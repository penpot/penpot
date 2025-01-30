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
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.modal :as modal]
   [app.main.data.tokens :as dt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*]]
   [app.main.ui.workspace.tokens.changes :as wtch]
   [app.main.ui.workspace.tokens.token :as wtt]
   [app.main.ui.workspace.tokens.token-types :as wtty]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.timers :as timers]
   [okulary.core :as l]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

;; Actions ---------------------------------------------------------------------

(defn attribute-actions [token selected-shapes attributes]
  (let [ids-by-attributes (wtt/shapes-ids-by-applied-attributes token selected-shapes attributes)
        shape-ids (into #{} (map :id selected-shapes))]
    {:all-selected? (wtt/shapes-applied-all? ids-by-attributes shape-ids attributes)
     :shape-ids shape-ids
     :selected-pred #(seq (% ids-by-attributes))}))

(defn generic-attribute-actions [attributes title {:keys [token selected-shapes on-update-shape]}]
  (let [on-update-shape-fn (or on-update-shape
                               (-> (wtty/get-token-properties token)
                                   (:on-update-shape)))
        {:keys [selected-pred shape-ids]} (attribute-actions token selected-shapes attributes)]
    (map (fn [attribute]
           (let [selected? (selected-pred attribute)
                 props {:attributes #{attribute}
                        :token token
                        :shape-ids shape-ids}]

             {:title title
              :selected? selected?
              :action (fn []
                        (if selected?
                          (st/emit! (wtch/unapply-token props))
                          (st/emit! (wtch/apply-token (assoc props :on-update-shape on-update-shape-fn)))))}))
         attributes)))

(defn all-or-sepearate-actions [{:keys [attribute-labels on-update-shape-all on-update-shape]}
                                {:keys [token selected-shapes]}]
  (let [attributes (set (keys attribute-labels))
        {:keys [all-selected? selected-pred shape-ids]} (attribute-actions token selected-shapes attributes)
        all-action (let [props {:attributes attributes
                                :token token
                                :shape-ids shape-ids}]
                     {:title (tr "labels.all")
                      :selected? all-selected?
                      :action #(if all-selected?
                                 (st/emit! (wtch/unapply-token props))
                                 (st/emit! (wtch/apply-token (assoc props :on-update-shape (or on-update-shape-all on-update-shape)))))})
        single-actions (map (fn [[attr title]]
                              (let [selected? (selected-pred attr)]
                                {:title title
                                 :selected? (and (not all-selected?) selected?)
                                 :action #(let [props {:attributes #{attr}
                                                       :token token
                                                       :shape-ids shape-ids}
                                                event (cond
                                                        all-selected? (-> (assoc props :attributes-to-remove attributes)
                                                                          (wtch/apply-token))
                                                        selected? (wtch/unapply-token props)
                                                        :else (-> (assoc props :on-update-shape on-update-shape)
                                                                  (wtch/apply-token)))]
                                            (st/emit! event))}))
                            attribute-labels)]
    (concat [all-action] single-actions)))

(defn spacing-attribute-actions [{:keys [token selected-shapes] :as context-data}]
  (let [on-update-shape-padding wtch/update-layout-padding
        padding-attrs {:p1 "Top"
                       :p2 "Right"
                       :p3 "Bottom"
                       :p4 "Left"}
        all-padding-attrs (into #{} (keys padding-attrs))
        {:keys [all-selected? selected-pred shape-ids]} (attribute-actions token selected-shapes all-padding-attrs)
        horizontal-attributes #{:p2 :p4}
        horizontal-padding-selected? (and
                                      (not all-selected?)
                                      (every? selected-pred horizontal-attributes))
        vertical-attributes #{:p1 :p3}
        vertical-padding-selected? (and
                                    (not all-selected?)
                                    (every? selected-pred vertical-attributes))
        padding-items [{:title (tr "labels.all")
                        :selected? all-selected?
                        :action (fn []
                                  (let [props {:attributes all-padding-attrs
                                               :token token
                                               :shape-ids shape-ids}]
                                    (if all-selected?
                                      (st/emit! (wtch/unapply-token props))
                                      (st/emit! (wtch/apply-token (assoc props :on-update-shape on-update-shape-padding))))))}
                       {:title "Horizontal"
                        :selected? horizontal-padding-selected?
                        :action (fn []
                                  (let [props {:token token
                                               :shape-ids shape-ids}
                                        event (cond
                                                all-selected? (wtch/apply-token (assoc props :attributes-to-remove horizontal-attributes))
                                                horizontal-padding-selected? (wtch/apply-token (assoc props :attributes-to-remove horizontal-attributes))
                                                :else (wtch/apply-token (assoc props
                                                                               :attributes horizontal-attributes
                                                                               :on-update-shape on-update-shape-padding)))]
                                    (st/emit! event)))}
                       {:title "Vertical"
                        :selected? vertical-padding-selected?
                        :action (fn []
                                  (let [props {:token token
                                               :shape-ids shape-ids}
                                        event (cond
                                                all-selected? (wtch/apply-token (assoc props :attributes-to-remove vertical-attributes))
                                                vertical-padding-selected? (wtch/apply-token (assoc props :attributes-to-remove vertical-attributes))
                                                :else (wtch/apply-token (assoc props
                                                                               :attributes vertical-attributes
                                                                               :on-update-shape on-update-shape-padding)))]
                                    (st/emit! event)))}]
        single-padding-items (->> padding-attrs
                                  (map (fn [[attr title]]
                                         (let [same-axis-selected? (cond
                                                                     (get horizontal-attributes attr) horizontal-padding-selected?
                                                                     (get vertical-attributes attr) vertical-padding-selected?
                                                                     :else true)
                                               selected? (and
                                                          (not all-selected?)
                                                          (not same-axis-selected?)
                                                          (selected-pred attr))]
                                           {:title title
                                            :selected? selected?
                                            :action #(let [props {:attributes #{attr}
                                                                  :token token
                                                                  :shape-ids shape-ids}
                                                           event (cond
                                                                   all-selected? (-> (assoc props :attributes-to-remove all-padding-attrs)
                                                                                     (wtch/apply-token))
                                                                   selected? (wtch/unapply-token props)
                                                                   :else (-> (assoc props :on-update-shape on-update-shape-padding)
                                                                             (wtch/apply-token)))]
                                                       (st/emit! event))}))))
        gap-items (all-or-sepearate-actions {:attribute-labels {:column-gap "Column Gap"
                                                                :row-gap "Row Gap"}
                                             :on-update-shape wtch/update-layout-spacing}
                                            context-data)]
    (concat padding-items
            single-padding-items
            [:separator]
            gap-items)))

(defn sizing-attribute-actions [context-data]
  (concat
   (all-or-sepearate-actions {:attribute-labels {:width "Width"
                                                 :height "Height"}
                              :on-update-shape wtch/update-shape-dimensions}
                             context-data)
   [:separator]
   (all-or-sepearate-actions {:attribute-labels {:layout-item-min-w "Min Width"
                                                 :layout-item-min-h "Min Height"}
                              :on-update-shape wtch/update-layout-sizing-limits}
                             context-data)
   [:separator]
   (all-or-sepearate-actions {:attribute-labels {:layout-item-max-w "Max Width"
                                                 :layout-item-max-h "Max Height"}
                              :on-update-shape wtch/update-layout-sizing-limits}
                             context-data)))

(defn update-shape-radius-for-corners [value shape-ids attributes]
  (st/emit!
   (ptk/data-event :expand-border-radius)
   (wtch/update-shape-radius-for-corners value shape-ids attributes)))

(def shape-attribute-actions-map
  (let [stroke-width (partial generic-attribute-actions #{:stroke-width} "Stroke Width")]
    {:border-radius (partial all-or-sepearate-actions {:attribute-labels {:r1 "Top Left"
                                                                          :r2 "Top Right"
                                                                          :r4 "Bottom Left"
                                                                          :r3 "Bottom Right"}
                                                       :on-update-shape-all wtch/update-shape-radius-all
                                                       :on-update-shape update-shape-radius-for-corners})
     :color (fn [context-data]
              [(generic-attribute-actions #{:fill} "Fill" (assoc context-data :on-update-shape wtch/update-fill))
               (generic-attribute-actions #{:stroke-color} "Stroke" (assoc context-data :on-update-shape wtch/update-stroke-color))])
     :spacing spacing-attribute-actions
     :sizing sizing-attribute-actions
     :rotation (partial generic-attribute-actions #{:rotation} "Rotation")
     :opacity (partial generic-attribute-actions #{:opacity} "Opacity")
     :stroke-width stroke-width
     :dimensions (fn [context-data]
                   (concat
                    [{:title "Sizing" :submenu :sizing}
                     {:title "Spacing" :submenu :spacing}
                     :separator
                     {:title "Border Radius" :submenu :border-radius}]
                    [:separator]
                    (stroke-width (assoc context-data :on-update-shape wtch/update-stroke-width))
                    [:separator]
                    (generic-attribute-actions #{:x} "X" (assoc context-data :on-update-shape wtch/update-shape-position))
                    (generic-attribute-actions #{:y} "Y" (assoc context-data :on-update-shape wtch/update-shape-position))))}))

(defn default-actions [{:keys [token selected-token-set-name]}]
  (let [{:keys [modal]} (wtty/get-token-properties token)]
    [{:title (tr "workspace.token.edit")
      :no-selectable true
      :action (fn [event]
                (let [{:keys [key fields]} modal]
                  (st/emit! dt/hide-token-context-menu)
                  (dom/stop-propagation event)
                  (modal/show! key {:x (.-clientX ^js event)
                                    :y (.-clientY ^js event)
                                    :position :right
                                    :fields fields
                                    :action "edit"
                                    :selected-token-set-name selected-token-set-name
                                    :token token})))}
     {:title (tr "workspace.token.duplicate")
      :no-selectable true
      :action #(st/emit! (dt/duplicate-token (:name token)))}
     {:title (tr "workspace.token.delete")
      :no-selectable true
      :action #(st/emit! (dt/delete-token
                          (ctob/prefixed-set-path-string->set-name-string selected-token-set-name)
                          (:name token)))}]))

(defn selection-actions [{:keys [type token] :as context-data}]
  (let [with-actions (get shape-attribute-actions-map (or type (:type token)))
        attribute-actions (if with-actions (with-actions context-data) [])]
    (concat
     attribute-actions
     (when (seq attribute-actions) [:separator])
     (default-actions context-data))))

(defn submenu-actions-selection-actions [{:keys [type token] :as context-data}]
  (let [with-actions (get shape-attribute-actions-map (or type (:type token)))
        attribute-actions (if with-actions (with-actions context-data) [])]
    (concat
     attribute-actions)))

;; Components ------------------------------------------------------------------

(def tokens-menu-ref
  (l/derived :token-context-menu refs/workspace-local))

(defn- prevent-default
  [event]
  (dom/prevent-default event)
  (dom/stop-propagation event))

(mf/defc menu-entry
  {::mf/props :obj}
  [{:keys [title value on-click selected? children submenu-offset submenu-direction no-selectable]}]
  (let [submenu-ref (mf/use-ref nil)
        hovering?   (mf/use-ref false)
        on-pointer-enter
        (mf/use-fn
         (fn []
           (mf/set-ref-val! hovering? true)
           (when-let [submenu-node (mf/ref-val submenu-ref)]
             (dom/set-css-property! submenu-node "display" "block"))))

        on-pointer-leave
        (mf/use-fn
         (fn []
           (mf/set-ref-val! hovering? false)
           (when-let [submenu-node (mf/ref-val submenu-ref)]
             (timers/schedule 50 #(when-not (mf/ref-val hovering?)
                                    (dom/set-css-property! submenu-node "display" "none"))))))

        set-dom-node
        (mf/use-fn
         (fn [dom]
           (let [submenu-node (mf/ref-val submenu-ref)]
             (when (and (some? dom) (some? submenu-node) (= submenu-direction "up"))
               (dom/set-css-property! submenu-node "top" "unset"))
             (when (and (some? dom) (some? submenu-node) (= submenu-direction "down"))
               (dom/set-css-property! submenu-node "top" (dm/str (.-offsetTop dom) "px"))))))]

    (mf/use-effect
     (mf/deps submenu-direction)
     (fn []
       (let [submenu-node (mf/ref-val submenu-ref)]
         (when (= submenu-direction "up")
           (dom/set-css-property! submenu-node "top" "unset")))))

    [:li {:class (stl/css :context-menu-item)
          :ref set-dom-node
          :data-value value
          :on-click on-click
          :on-pointer-enter on-pointer-enter
          :on-pointer-leave on-pointer-leave}
     (when selected?
       [:> icon* {:icon-id "tick" :size "s" :class (stl/css :icon-wrapper)}])
     [:span {:class (stl/css-case :item-text true
                                  :item-with-icon-space (and
                                                         (not selected?)
                                                         (not no-selectable)))}
      title]
     (when children
       [:*
        [:> icon* {:icon-id "arrow" :size "s"}]
        [:ul {:class (stl/css :token-context-submenu)
              :data-direction submenu-direction
              :ref submenu-ref
              ;; Under review: This distances are arbitrary,
              ;; https://tree.taiga.io/project/penpot/task/9627
              :style {:display "none"
                      :--dist (if (= submenu-direction "down")
                                "-80px"
                                "80px")
                      :left (dm/str submenu-offset "px")}
              :on-context-menu prevent-default}
         children]])]))

(mf/defc menu-tree
  [{:keys [selected-shapes submenu-offset submenu-direction type errors] :as context-data}]
  (let [entries (if (and (not (some? errors))
                         (seq selected-shapes))
                  (if (some? type)
                    (submenu-actions-selection-actions context-data)
                    (selection-actions context-data))
                  (default-actions context-data))]
    (for [[index {:keys [title action selected? submenu no-selectable] :as entry}] (d/enumerate entries)]
      [:* {:key (dm/str title " " index)}
       (cond
         (= :separator entry) [:li {:class (stl/css :separator)}]
         submenu [:& menu-entry {:title title
                                 :no-selectable true
                                 :submenu-direction submenu-direction
                                 :submenu-offset submenu-offset}
                  [:& menu-tree (assoc context-data :type submenu)]]
         :else [:& menu-entry
                {:title title
                 :on-click action
                 :no-selectable no-selectable
                 :selected? selected?}])])))

(mf/defc token-context-menu-tree
  [{:keys [width direction errors] :as mdata}]
  (let [objects (mf/deref refs/workspace-page-objects)
        selected (mf/deref refs/selected-shapes)
        selected-shapes (into [] (keep (d/getf objects)) selected)
        token-name (:token-name mdata)
        token (mf/deref (refs/workspace-selected-token-set-token token-name))
        selected-token-set-name (mf/deref refs/workspace-selected-token-set-name)]
    [:ul {:class (stl/css :context-list)}
     [:& menu-tree {:submenu-offset width
                    :submenu-direction direction
                    :token token
                    :errors errors
                    :selected-token-set-name selected-token-set-name
                    :selected-shapes selected-shapes}]]))

(mf/defc token-context-menu
  []
  (let [mdata               (mf/deref tokens-menu-ref)
        is-open?            (boolean mdata)
        width               (mf/use-state 0)
        dropdown-ref        (mf/use-ref)
        dropdown-direction* (mf/use-state "down")
        dropdown-direction  (deref dropdown-direction*)
        dropdown-direction-change* (mf/use-ref 0)
        top                 (+ (get-in mdata [:position :y]) 5)
        left                (+ (get-in mdata [:position :x]) 5)]

    (mf/use-effect
     (mf/deps is-open?)
     (fn []
       (when-let [node (mf/ref-val dropdown-ref)]
         (reset! width (.-offsetWidth node)))))

    (mf/with-effect [is-open?]
      (when (and (not= 0 (mf/ref-val dropdown-direction-change*)) (= false is-open?))
        (reset! dropdown-direction* "down")
        (mf/set-ref-val! dropdown-direction-change* 0)))

    (mf/with-effect [is-open? dropdown-ref]
      (let [dropdown-element (mf/ref-val dropdown-ref)]
        (when (and (= 0 (mf/ref-val dropdown-direction-change*)) dropdown-element)
          (let [is-outside? (dom/is-element-outside? dropdown-element)]
            (reset! dropdown-direction* (if is-outside? "up" "down"))
            (mf/set-ref-val! dropdown-direction-change* (inc (mf/ref-val dropdown-direction-change*)))))))

    [:& dropdown {:show is-open?
                  :on-close #(st/emit! dt/hide-token-context-menu)}
     [:div {:class (stl/css :token-context-menu)
            :data-testid "tokens-context-menu-for-token"
            :ref dropdown-ref
            :data-direction dropdown-direction
            :style {:--bottom (if (= dropdown-direction "up")
                                "40px"
                                "unset")
                    :--top (dm/str top "px")
                    :left (dm/str left "px")}
            :on-context-menu prevent-default}
      (when mdata
        [:& token-context-menu-tree (assoc mdata :width @width :direction dropdown-direction)])]]))
