;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.sidebar.options.text
  (:require
   [rumext.alpha :as mf]
   [okulary.core :as l]
   [uxbox.builtins.icons :as i]
   [uxbox.common.data :as d]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.data.workspace.texts :as dwt]
   [uxbox.main.store :as st]
   [uxbox.main.refs :as refs]
   [uxbox.main.ui.workspace.sidebar.options.measures :refer [measures-menu]]
   [uxbox.util.dom :as dom]
   [uxbox.main.fonts :as fonts]
   [uxbox.util.math :as math]
   [uxbox.util.i18n :as i18n :refer [tr t]]
   ["slate" :refer [Transforms]]))

(def ^:private editor-ref
  (l/derived :editor refs/workspace-local))

(mf/defc font-select-optgroups
  {::mf/wrap [mf/memo]}
  []
  [:*
   [:optgroup {:label "Local"}
    (for [font fonts/local-fonts]
      [:option {:value (:id font)
                :key (:id font)}
       (:name font)])]
   [:optgroup {:label "Google"}
    (for [font (fonts/resolve-fonts :google)]
      [:option {:value (:id font)
                :key (:id font)}
       (:name font)])]])

(mf/defc font-options
  [{:keys [editor shape] :as props}]
  (let [selection (mf/use-ref)
        font-id   (dwt/current-font-family editor {:default "sourcesanspro"})
        font-size (dwt/current-font-size editor {:default "14"})
        font-var  (dwt/current-font-variant editor {:default "regular"})

        fonts     (mf/deref fonts/fontsdb)
        font      (get fonts font-id)

        on-font-family-change
        (fn [event]
          (let [id (-> (dom/get-target event)
                       (dom/get-value))
                font (get fonts id)]
            (fonts/ensure-loaded! id
                                  #(do
                                     (dwt/set-font! editor id (:family font))
                                     (when (not= id font-id)
                                       (dwt/set-font-variant! editor nil nil nil))))))

        on-font-size-change
        (fn [event]
          (let [val (-> (dom/get-target event)
                        (dom/get-value))]
            (dwt/set-font-size! editor val)))

        on-font-variant-change
        (fn [event]
          (let [id (-> (dom/get-target event)
                       (dom/get-value))
                variant (d/seek #(= id (:name %)) (:variants font))]
            (dwt/set-font! editor (:id font) (:family font))
            (dwt/set-font-variant! editor id (:weight variant) (:style variant))))
        ]
    [:*
     [:div.row-flex
      [:select.input-select {:value font-id
                             :on-change on-font-family-change}
       [:& font-select-optgroups]]]

     [:div.row-flex
      [:div.editable-select
       [:select.input-select {:value font-size
                              :on-change on-font-size-change}
        [:option {:value "8"} "8"]
        [:option {:value "9"} "9"]
        [:option {:value "10"} "10"]
        [:option {:value "11"} "11"]
        [:option {:value "12"} "12"]
        [:option {:value "14"} "14"]
        [:option {:value "18"} "18"]
        [:option {:value "24"} "24"]
        [:option {:value "36"} "36"]
        [:option {:value "48"} "48"]
        [:option {:value "72"} "72"]]
       [:input.input-text {:type "number"
                           :min "0"
                           :max "200"
                           :value font-size
                           :on-change on-font-size-change
                           }]]

      [:select.input-select {:value font-var
                             :on-change on-font-variant-change}
       (for [variant (:variants font)]
         [:option {:value (:name variant)
                   :key (pr-str variant)}
          (:name variant)])]]]))


(mf/defc text-align-options
  [{:keys [editor] :as props}]
  (let [on-text-align-change
       (fn [event type]
         (js/console.log (dwt/set-text-align! editor type)))]
      ;; --- Align

    [:div.row-flex.align-icons
     [:span.tooltip.tooltip-bottom
      {:alt "Align left"
       :class (dom/classnames
               :current (dwt/text-align-enabled? editor "left"))
       :on-click #(on-text-align-change % "left")}
      i/text-align-left]
     [:span.tooltip.tooltip-bottom
      {:alt "Align center"
       :class (dom/classnames
               :current (dwt/text-align-enabled? editor "center"))
       :on-click #(on-text-align-change % "center")}
      i/text-align-center]
     [:span.tooltip.tooltip-bottom
      {:alt "Align right"
       :class (dom/classnames
               :current (dwt/text-align-enabled? editor "right"))
       :on-click #(on-text-align-change % "right")}
      i/text-align-right]
     [:span.tooltip.tooltip-bottom
      {:alt "Justify"
       :class (dom/classnames
               :current (dwt/text-align-enabled? editor "justify"))
       :on-click #(on-text-align-change % "justify")}
      i/text-align-justify]]))

(mf/defc spacing-options
  [{:keys [editor] :as props}]
  (let [selection (mf/use-ref)
        lh (dwt/current-line-height editor {:default "1.2"
                                            :at (mf/ref-val selection)})
        ls (dwt/current-letter-spacing editor {:default "0"
                                               :at (mf/ref-val selection)})]
    [:div.row-flex
     [:div.input-icon
      [:span.icon-before.tooltip.tooltip-bottom
       {:alt "Line height"}
       i/line-height]
      [:input.input-text
       {:type "number"
        :step "0.1"
        :min "0"
        :max "200"
        :value lh
        :on-focus  (fn [event]
                     (js/console.log "line-height on-focus")
                     ;; (mf/set-ref-val! selection (.-selection editor))
                     (dom/prevent-default event)
                     (dom/stop-propagation event))
        :on-blur   (fn [event]
                     ;; (js/console.log "line-height on-blur")
                     (mf/set-ref-val! selection nil)
                     (dom/prevent-default event)
                     (dom/stop-propagation event))
        :on-change (fn [event]
                     (let [val (-> (dom/get-target event)
                                   (dom/get-value))
                           sel (mf/ref-val selection)]
                       ;; (js/console.log "line-height on-change" sel val)
                       (dwt/set-line-height! editor val sel)))}]]
     [:div.input-icon
      [:span.icon-before.tooltip.tooltip-bottom
       {:alt "Letter spacing"}
       i/letter-spacing]
      [:input.input-text
       {:type "number"
        :step "0.1"
        :min "0"
        :max "200"
        :value ls
        :on-focus  (fn [event]
                     ;; (js/console.log "letter-spacing on-focus")
                     (mf/set-ref-val! selection (.-selection editor))
                     (dom/prevent-default event)
                     (dom/stop-propagation event))
        :on-blur   (fn [event]
                     ;; (js/console.log "letter-spacing on-blur")
                     (mf/set-ref-val! selection nil)
                     (dom/prevent-default event)
                     (dom/stop-propagation event))
        :on-change (fn [event]
                     (let [val (-> (dom/get-target event)
                                   (dom/get-value))
                           sel (mf/ref-val selection)]
                       ;; (js/console.log "letter-spacing on-change" sel val)
                       (dwt/set-letter-spacing! editor val sel)))}]]]))

(mf/defc box-sizing-options
  [{:keys [editor] :as props}]
  [:div.align-icons
   [:span.tooltip.tooltip-bottom
    {:alt "Auto height"}
    i/auto-height]
   [:span.tooltip.tooltip-bottom
    {:alt "Auto width"}
    i/auto-width]
   [:span.tooltip.tooltip-bottom
    {:alt "Fixed size"}
    i/auto-fix]])

(mf/defc vertical-align-options
  [{:keys [editor] :as props}]
  (let [on-vertical-align-change
        (fn [event type]
          (dwt/set-vertical-align! editor type))]
    [:div.align-icons
     [:span.tooltip.tooltip-bottom
      {:alt "Align top"
       :class (dom/classnames
               :current (dwt/vertical-align-enabled? editor "top"))
       :on-click #(on-vertical-align-change % "top")}
      i/align-top]
     [:span.tooltip.tooltip-bottom
      {:alt "Align middle"
       :class (dom/classnames
               :current (dwt/vertical-align-enabled? editor "center"))
       :on-click #(on-vertical-align-change % "center")}
      i/align-middle]
     [:span.tooltip.tooltip-bottom
      {:alt "Align bottom"
       :class (dom/classnames
               :current (dwt/vertical-align-enabled? editor "bottom"))
       :on-click #(on-vertical-align-change % "bottom")}
      i/align-bottom]]))

(mf/defc text-decoration-options
  [{:keys [editor] :as props}]
  (let [on-decoration-change
        (fn [event type]
          (dom/prevent-default event)
          (dom/stop-propagation event)
          (if (dwt/text-decoration-enabled? editor type)
            (dwt/set-text-decoration! editor "none")
            (dwt/set-text-decoration! editor type)))]
    [:div.row-flex
     [:span.element-set-subtitle "Decoration"]
     [:div.align-icons
      [:span.tooltip.tooltip-bottom
       {:alt "None"
        :on-click #(on-decoration-change % "none")}
       i/minus]

      [:span.tooltip.tooltip-bottom
         {:alt "Underline"
          :class (dom/classnames
                  :current (dwt/text-decoration-enabled? editor "underline"))
          :on-click #(on-decoration-change % "underline")}
       i/underline]

      [:span.tooltip.tooltip-bottom
       {:alt "Strikethrough"
        :class (dom/classnames
                :current (dwt/text-decoration-enabled? editor "line-through"))
        :on-click #(on-decoration-change % "line-through")}
       i/strikethrough]]]))


(mf/defc text-transform-options
  [{:keys [editor] :as props}]
  (let [on-text-transform-change
        (fn [event type]
          (dom/prevent-default event)
          (dom/stop-propagation event)
          (if (dwt/text-transform-enabled? editor type)
            (dwt/set-text-transform! editor "none")
            (dwt/set-text-transform! editor type)))]
    [:div.row-flex
     [:span.element-set-subtitle "Case"]
     [:div.align-icons
      [:span.tooltip.tooltip-bottom
       {:alt "None"
        :class (dom/classnames
                :current (dwt/text-transform-enabled? editor "none"))
        :on-click #(on-text-transform-change % "none")}

       i/minus]
      [:span.tooltip.tooltip-bottom
       {:alt "Uppercase"
        :class (dom/classnames
                :current (dwt/text-transform-enabled? editor "uppercase"))
        :on-click #(on-text-transform-change % "uppercase")}

       i/uppercase]
      [:span.tooltip.tooltip-bottom
       {:alt "Lowercase"
        :class (dom/classnames
                :current (dwt/text-transform-enabled? editor "lowercase"))
        :on-click #(on-text-transform-change % "lowercase")}

       i/lowercase]
      [:span.tooltip.tooltip-bottom
       {:alt "Titlecase"
        :class (dom/classnames
                :current (dwt/text-transform-enabled? editor "capitalize"))
        :on-click #(on-text-transform-change % "capitalize")}
       i/titlecase]]]))

(mf/defc text-menu
  {::mf/wrap [mf/memo]}
  [{:keys [shape] :as props}]
  (let [id (:id shape)
        editor (:editor (mf/deref refs/workspace-local))
        locale (i18n/use-locale)]

    [:div.element-set
     [:div.element-set-title (t locale "workspace.options.font-options")]
     [:div.element-set-content
      [:& font-options {:editor editor}]
      [:& text-align-options {:editor editor}]
      [:& spacing-options {:editor editor}]

      [:div.row-flex
       [:& vertical-align-options {:editor editor}]
       [:& box-sizing-options {:editor editor}]]

      [:& text-decoration-options {:editor editor}]
      [:& text-transform-options {:editor editor}]]]))

(mf/defc options
  [{:keys [shape] :as props}]
  [:div
   [:& measures-menu {:shape shape}]
   [:& text-menu {:shape shape}]])
