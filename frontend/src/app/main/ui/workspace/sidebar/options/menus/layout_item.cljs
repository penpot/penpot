;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.layout-item
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace :as udw]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :refer [numeric-input]]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.menus.layout-container :refer [get-layout-flex-icon]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def layout-item-attrs
  [:layout-item-margin      ;; {:m1 0 :m2 0 :m3 0 :m4 0}
   :layout-item-margin-type ;; :simple :multiple
   :layout-item-h-sizing    ;; :fill :fix :auto
   :layout-item-v-sizing    ;; :fill :fix :auto
   :layout-item-max-h       ;; num
   :layout-item-min-h       ;; num
   :layout-item-max-w       ;; num
   :layout-item-min-w       ;; num
   :layout-item-align-self  ;; :start :end :center :stretch :baseline
   :layout-item-absolute
   :layout-item-z-index])

(mf/defc margin-section
  [{:keys [values change-margin-style on-margin-change] :as props}]

  (let [margin-type (or (:layout-item-margin-type values) :simple)
        m1 (if (and (not (= :multiple (:layout-item-margin values)))
                    (= (dm/get-in values [:layout-item-margin :m1])
                       (dm/get-in values [:layout-item-margin :m3])))
             (dm/get-in values [:layout-item-margin :m1])
             "--")

        m2 (if (and (not (= :multiple (:layout-item-margin values)))
                    (= (dm/get-in values [:layout-item-margin :m2])
                       (dm/get-in values [:layout-item-margin :m4])))
             (dm/get-in values [:layout-item-margin :m2])
             "--")
        select-margins
        (fn [m1? m2? m3? m4?]
          (st/emit! (udw/set-margins-selected {:m1 m1? :m2 m2? :m3 m3? :m4 m4?})))

        select-margin #(select-margins (= % :m1) (= % :m2) (= % :m3) (= % :m4))]

    (mf/use-effect
     (fn []
       (fn []
         ;;on destroy component
         (select-margins false false false false))))

    [:div.margin-row
     (cond
       (= margin-type :simple)

       [:div.margin-item-group
        [:div.margin-item.tooltip.tooltip-bottom-left
         {:alt "Vertical margin"}
         [:span.icon i/auto-margin-both-sides]
         [:> numeric-input
          {:placeholder "--"
           :on-focus (fn [event]
                       (select-margins true false true false)
                       (dom/select-target event))
           :on-change (partial on-margin-change :simple :m1)
           :on-blur #(select-margins false false false false)
           :value m1}]]

        [:div.margin-item.tooltip.tooltip-bottom-left
         {:alt "Horizontal margin"}
         [:span.icon.rotated i/auto-margin-both-sides]
         [:> numeric-input
          {:placeholder "--"
           :on-focus (fn [event]
                       (select-margins false true false true)
                       (dom/select-target event))
           :on-change (partial on-margin-change :simple :m2)
           :on-blur #(select-margins false false false false)
           :value m2}]]]

       (= margin-type :multiple)
       [:div.wrapper
        (for [num [:m1 :m2 :m3 :m4]]
          [:div.tooltip.tooltip-bottom
           {:key (dm/str "margin-" (d/name num))
            :alt (case num
                   :m1 "Top"
                   :m2 "Right"
                   :m3 "Bottom"
                   :m4 "Left")}
           [:div.input-element.auto
            [:> numeric-input
             {:placeholder "--"
              :on-focus (fn [event]
                          (select-margin num)
                          (dom/select-target event))
              :on-change (partial on-margin-change :multiple num)
              :on-blur #(select-margins false false false false)
              :value (num (:layout-item-margin values))}]]])])

     [:div.margin-item-icons
      [:div.margin-item-icon.tooltip.tooltip-bottom-left
       {:class (dom/classnames :selected (= margin-type :multiple))
        :alt "Margin - multiple"
        :on-click #(change-margin-style (if (= margin-type :multiple) :simple :multiple))}
       i/auto-margin]]]))

(mf/defc element-behavior
  [{:keys [is-layout-container? is-layout-child? layout-item-h-sizing layout-item-v-sizing on-change-behavior] :as props}]
  (let [fill? is-layout-child?
        auto? is-layout-container?]

    [:div.btn-wrapper
     {:class (when (and fill? auto?) "wrap")}
     [:div.layout-behavior.horizontal
      [:button.behavior-btn.tooltip.tooltip-bottom
       {:alt "Fix width"
        :class  (dom/classnames :active (= layout-item-h-sizing :fix))
        :on-click #(on-change-behavior :h :fix)}
       i/auto-fix-layout]
      (when fill?
        [:button.behavior-btn.tooltip.tooltip-bottom
         {:alt "Width 100%"
          :class  (dom/classnames :active (= layout-item-h-sizing :fill))
          :on-click #(on-change-behavior :h :fill)}
         i/auto-fill])
      (when auto?
        [:button.behavior-btn.tooltip.tooltip-bottom
         {:alt "Fit content"
          :class  (dom/classnames :active (= layout-item-h-sizing :auto))
          :on-click #(on-change-behavior :h :auto)}
         i/auto-hug])]

     [:div.layout-behavior
      [:button.behavior-btn.tooltip.tooltip-bottom
       {:alt "Fix height"
        :class  (dom/classnames :active (= layout-item-v-sizing :fix))
        :on-click #(on-change-behavior :v :fix)}
       i/auto-fix-layout]
      (when fill?
        [:button.behavior-btn.tooltip.tooltip-bottom-left
         {:alt "Height 100%"
          :class  (dom/classnames :active (= layout-item-v-sizing :fill))
          :on-click #(on-change-behavior :v :fill)}
         i/auto-fill])
      (when auto?
        [:button.behavior-btn.tooltip.tooltip-bottom-left
         {:alt "Fit content"
          :class  (dom/classnames :active (= layout-item-v-sizing :auto))
          :on-click #(on-change-behavior :v :auto)}
         i/auto-hug])]]))


(mf/defc align-self-row
  [{:keys [is-col? align-self set-align-self] :as props}]
  (let [dir-v [:start :center :end #_:stretch #_:baseline]]
    [:div.align-self-style
     (for [align dir-v]
       [:button.align-self.tooltip.tooltip-bottom
        {:class    (dom/classnames :active  (= align-self align)
                                   :tooltip-bottom-left (not= align :start)
                                   :tooltip-bottom (= align :start))
         :alt      (dm/str "Align self " (d/name align))
         :on-click #(set-align-self align)
         :key (str "align-self" align)}
        (get-layout-flex-icon :align-self align is-col?)])]))

(mf/defc layout-item-menu
  {::mf/wrap [#(mf/memo' % (mf/check-props ["ids" "values" "type" "is-layout-child?"]))]}
  [{:keys [ids values is-layout-child? is-layout-container?] :as props}]

  (let [selection-parents-ref (mf/use-memo (mf/deps ids) #(refs/parents-by-ids ids))
        selection-parents     (mf/deref selection-parents-ref)

        change-margin-style
        (fn [type]
          (st/emit! (dwsl/update-layout-child ids {:layout-item-margin-type type})))

        align-self         (:layout-item-align-self values)
        set-align-self     (fn [value]
                             (if (= align-self value)
                               (st/emit! (dwsl/update-layout-child ids {:layout-item-align-self nil}))
                               (st/emit! (dwsl/update-layout-child ids {:layout-item-align-self value}))))

        is-col? (every? ctl/col? selection-parents)

        on-margin-change
        (fn [type prop val]
          (cond
            (and (= type :simple) (= prop :m1))
            (st/emit! (dwsl/update-layout-child ids {:layout-item-margin {:m1 val :m3 val}}))

            (and (= type :simple) (= prop :m2))
            (st/emit! (dwsl/update-layout-child ids {:layout-item-margin {:m2 val :m4 val}}))

            :else
            (st/emit! (dwsl/update-layout-child ids {:layout-item-margin {prop val}}))))

        on-change-behavior
        (fn [dir value]
          (if (= dir :h)
            (st/emit! (dwsl/update-layout-child ids {:layout-item-h-sizing value}))
            (st/emit! (dwsl/update-layout-child ids {:layout-item-v-sizing value}))))

        on-size-change
        (fn [measure value]
          (st/emit! (dwsl/update-layout-child ids {measure value})))

        on-change-position
        (fn [value]
          (st/emit! (dwsl/update-layout-child ids {:layout-item-absolute (= value :absolute)})))

        on-change-z-index
        (fn [value]
          (st/emit! (dwsl/update-layout-child ids {:layout-item-z-index value})))]

    [:div.element-set
     [:div.element-set-title
      [:span (if (and is-layout-container? (not is-layout-child?))
               "Flex board"
               "Flex element")]]

     [:div.element-set-content.layout-item-menu
      (when is-layout-child?
        [:div.layout-row
         [:div.row-title.sizing "Position"]
         [:div.btn-wrapper
          [:div.absolute
           [:button.behavior-btn.tooltip.tooltip-bottom
            {:alt "Static"
             :class  (dom/classnames :active (not (:layout-item-absolute values)))
             :on-click #(on-change-position :static)}
            "Static"]
           [:button.behavior-btn.tooltip.tooltip-bottom
            {:alt "Absolute"
             :class  (dom/classnames :active (and (:layout-item-absolute values) (not= :multiple (:layout-item-absolute values))))
             :on-click #(on-change-position :absolute)}
            "Absolute"]]

          [:div.tooltip.tooltip-bottom-left.z-index {:alt "z-index"}
           i/layers
           [:> numeric-input
            {:placeholder "--"
             :on-focus #(dom/select-target %)
             :on-change #(on-change-z-index %)
             :value (:layout-item-z-index values)}]]]])

      (when (not (:layout-item-absolute values))
        [:*
         [:div.layout-row
          [:div.row-title.sizing "Sizing"]
          [:& element-behavior {:is-layout-child? is-layout-child?
                                :is-layout-container? is-layout-container?
                                :layout-item-v-sizing (or (:layout-item-v-sizing values) :fix)
                                :layout-item-h-sizing (or (:layout-item-h-sizing values) :fix)
                                :on-change-behavior on-change-behavior}]]

         (when is-layout-child?
           [:div.layout-row
            [:div.row-title "Align"]
            [:div.btn-wrapper
             [:& align-self-row {:is-col? is-col?
                                 :align-self align-self
                                 :set-align-self set-align-self}]]])

         (when is-layout-child?
           [:& margin-section {:values values
                               :change-margin-style change-margin-style
                               :on-margin-change on-margin-change}])

         [:div.advanced-ops-body
          [:div.input-wrapper
           (for  [item (cond-> []
                         (= (:layout-item-h-sizing values) :fill)
                         (conj :layout-item-min-w :layout-item-max-w)

                         (= (:layout-item-v-sizing values) :fill)
                         (conj :layout-item-min-h :layout-item-max-h))]

             [:div.tooltip.tooltip-bottom
              {:key   (d/name item)
               :alt   (tr (dm/str "workspace.options.layout-item.title." (d/name item)))
               :class (dom/classnames "maxH" (= item :layout-item-max-h)
                                      "minH" (= item :layout-item-min-h)
                                      "maxW" (= item :layout-item-max-w)
                                      "minW" (= item :layout-item-min-w))}
              [:div.input-element
               {:alt   (tr (dm/str "workspace.options.layout-item." (d/name item)))}
               [:> numeric-input
                {:no-validate true
                 :min 0
                 :data-wrap true
                 :placeholder "--"
                 :on-focus #(dom/select-target %)
                 :on-change (partial on-size-change item)
                 :value (get values item)
                 :nillable true}]]])]]])]]))
