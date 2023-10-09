;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.text
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.text :as txt]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.data.workspace.texts :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.menus.typography :refer [typography-entry text-options]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.timers :as ts]
   [rumext.v2 :as mf]))

(mf/defc text-align-options
  [{:keys [values on-change on-blur] :as props}]
  (let [{:keys [text-align]} values
        new-css-system (mf/use-ctx ctx/new-css-system)

        handle-change
        (mf/use-fn
         (fn [value]
           (let [new-align (if new-css-system
                             value
                             (-> (dom/get-current-target value)
                                 (dom/get-data "value")))]
             (on-change {:text-align new-align})
             (when (some? on-blur) (on-blur)))))]

    ;; --- Align
    (if new-css-system
      [:div {:class (stl/css :align-options)}
       [:& radio-buttons {:selected text-align
                          :on-change handle-change
                          :name "align-text-options"}
        [:& radio-button {:value "left"
                          :id "text-align-left"
                          :title (tr "workspace.options.text-options.text-align-left" (sc/get-tooltip :text-align-left))
                          :icon i/text-align-left-refactor}]
        [:& radio-button {:value "center"
                          :id "text-align-center"
                          :title (tr "workspace.options.text-options.text-align-center" (sc/get-tooltip :text-align-center))
                          :icon i/text-align-center-refactor}]
        [:& radio-button {:value "right"
                          :id "text-align-right"
                          :title (tr "workspace.options.text-options.text-align-right" (sc/get-tooltip :text-align-right))
                          :icon i/text-align-right-refactor}]
        [:& radio-button {:value "justify"
                          :id "text-align-justify"
                          :title (tr "workspace.options.text-options.text-align-justify" (sc/get-tooltip :text-align-justify))
                          :icon i/text-justify-refactor}]]]
      [:div.align-icons
       [:span.tooltip.tooltip-bottom
        {:alt (tr "workspace.options.text-options.text-align-left" (sc/get-tooltip :text-align-left))
         :class (dom/classnames :current (= "left" text-align))
         :data-value "left"
         :on-click handle-change}
        i/text-align-left]
       [:span.tooltip.tooltip-bottom
        {:alt (tr "workspace.options.text-options.text-align-center" (sc/get-tooltip :text-align-center))
         :class (dom/classnames :current (= "center" text-align))
         :data-value "center"
         :on-click handle-change}
        i/text-align-center]
       [:span.tooltip.tooltip-bottom
        {:alt (tr "workspace.options.text-options.text-align-right" (sc/get-tooltip :text-align-right))
         :class (dom/classnames :current (= "right" text-align))
         :data-value "right"
         :on-click handle-change}
        i/text-align-right]
       [:span.tooltip.tooltip-bottom
        {:alt (tr "workspace.options.text-options.text-align-justify" (sc/get-tooltip :text-align-justify))
         :class (dom/classnames :current (= "justify" text-align))
         :data-value "justify"
         :on-click handle-change}
        i/text-align-justify]])))

(mf/defc text-direction-options
  [{:keys [values on-change on-blur] :as props}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        direction     (:text-direction values)
        handle-change
        (mf/use-fn
         (mf/deps direction)
         (fn [value]
           (let [val (if new-css-system
                             value
                             (-> (dom/get-current-target value)
                                 (dom/get-data "value")))
                 dir (if (= val direction)
                       "none"
                       val)]
             (on-change {:text-direction dir})
             (when (some? on-blur) (on-blur)))))]

    (if new-css-system
      [:div {:class (stl/css :text-direction-options)}
       [:& radio-buttons {:selected direction
                          :on-change handle-change
                          :name "text-direction-options"}
        [:& radio-button {:value "ltr"
                          :type "checkbox"
                            :id "ltr-text-direction"
                            :title (tr "workspace.options.text-options.direction-ltr")
                            :icon i/text-ltr-refactor}]
        [:& radio-button {:value "rtl"
                          :type "checkbox"
                          :id "rtl-text-direction"
                          :title (tr "workspace.options.text-options.direction-rtl")
                          :icon i/text-rtl-refactor}]]]
    ;; --- Align
    [:div.align-icons
     [:span.tooltip.tooltip-bottom-left
      {:alt (tr "workspace.options.text-options.direction-ltr")
       :class (dom/classnames :current (= "ltr" direction))
       :data-value "ltr"
       :on-click handle-change}
      i/text-direction-ltr]
     [:span.tooltip.tooltip-bottom-left
      {:alt (tr "workspace.options.text-options.direction-rtl")
       :class (dom/classnames :current (= "rtl" direction))
       :data-value "rtl"
       :on-click handle-change}
      i/text-direction-rtl]])))

(mf/defc vertical-align
  [{:keys [values on-change on-blur] :as props}]
  (let [{:keys [vertical-align]} values
        new-css-system (mf/use-ctx ctx/new-css-system)
        vertical-align (or vertical-align "top")
        handle-change
        (mf/use-fn
         (fn [value]
           (let [new-align (if new-css-system
                             value
                             (-> (dom/get-current-target value)
                                 (dom/get-data "value")))]
             (on-change {:vertical-align new-align})
             (when (some? on-blur) (on-blur)))))]

    (if new-css-system
      [:div {:class (stl/css :vertical-align-options)}
       [:& radio-buttons {:selected vertical-align
                          :on-change handle-change
                          :name "vertical-align-text-options"}
        [:& radio-button {:value "top"
                          :id "vertical-text-align-top"
                          :title (tr "workspace.options.text-options.align-top")
                          :icon i/text-top-refactor}]
        [:& radio-button {:value "center"
                          :id "vertical-text-align-center"
                          :title (tr "workspace.options.text-options.align-middle")
                          :icon i/text-middle-refactor}]
        [:& radio-button {:value "bottom"
                          :id "vertical-text-align-bottom"
                          :title (tr "workspace.options.text-options.align-bottom")
                          :icon i/text-bottom-refactor}]]]
      [:div.align-icons
       [:span.tooltip.tooltip-bottom-left
        {:alt (tr "workspace.options.text-options.align-top")
         :class (dom/classnames :current (= "top" vertical-align))
         :data-value "top"
         :on-click handle-change}
        i/align-top]
       [:span.tooltip.tooltip-bottom-left
        {:alt (tr "workspace.options.text-options.align-middle")
         :class (dom/classnames :current (= "center" vertical-align))
         :data-value "center"
         :on-click handle-change}
        i/align-middle]
       [:span.tooltip.tooltip-bottom-left
        {:alt (tr "workspace.options.text-options.align-bottom")
         :class (dom/classnames :current (= "bottom" vertical-align))
         :data-value "bottom"
         :on-click handle-change}
        i/align-bottom]])))

(mf/defc grow-options
  [{:keys [ids values on-blur] :as props}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        grow-type (:grow-type values)

        handle-change-grow
        (mf/use-fn
         (mf/deps ids)
         (fn [value]
          (let [uid (js/Symbol)

                grow-type (if new-css-system
                            (keyword value)
                            (-> (dom/get-current-target value)
                                (dom/get-data "value")
                                (keyword)))]
            (st/emit!
             (dwu/start-undo-transaction uid)
             (dch/update-shapes ids #(assoc % :grow-type grow-type)))
            ;; We asynchronously commit so every sychronous event is resolved first and inside the transaction
            (ts/schedule #(st/emit! (dwu/commit-undo-transaction uid))))
          (when (some? on-blur) (on-blur))))]

    (if new-css-system
      [:div {:class (stl/css :grow-options)}
       [:& radio-buttons {:selected (d/name grow-type)
                          :on-change handle-change-grow
                          :name "grow-text-options"}
        [:& radio-button {:value "fixed"
                          :id "text-fixed-grow"
                          :title (tr "workspace.options.text-options.grow-fixed")
                          :icon i/text-fixed-refactor}]
        [:& radio-button {:value "auto-width"
                          :id "text-auto-width-grow"
                          :title (tr "workspace.options.text-options.grow-auto-width")
                          :icon i/text-auto-width-refactor}]
        [:& radio-button {:value "auto-height"
                          :id "text-auto-height-grow"
                          :title (tr "workspace.options.text-options.grow-auto-height")
                          :icon i/text-auto-height-refactor}]]]

      [:div.align-icons
       [:span.tooltip.tooltip-bottom
        {:alt (tr "workspace.options.text-options.grow-fixed")
         :class (dom/classnames :current (= :fixed grow-type))
         :data-value "fixed"
         :on-click handle-change-grow}
        i/auto-fix]
       [:span.tooltip.tooltip-bottom
        {:alt (tr "workspace.options.text-options.grow-auto-width")
         :data-value "auto-width"
         :class (dom/classnames :current (= :auto-width grow-type))
         :on-click handle-change-grow}
        i/auto-width]
       [:span.tooltip.tooltip-bottom
        {:alt (tr "workspace.options.text-options.grow-auto-height")
         :class (dom/classnames :current (= :auto-height grow-type))
         :data-value "auto-height"
         :on-click handle-change-grow}
        i/auto-height]])))

(mf/defc text-decoration-options
  [{:keys [values on-change on-blur] :as props}]
  (let [new-css-system  (mf/use-ctx ctx/new-css-system)
        text-decoration (or (:text-decoration values) "none")
        handle-change
        (mf/use-fn
         (mf/deps text-decoration)
         (fn [value]
           (let [val (if new-css-system
                       value
                       (-> (dom/get-current-target value)
                           (dom/get-data "value")))
                 decoration (if (= val text-decoration)
                              "none"
                              val)]
             (on-change {:text-decoration decoration})
             (when (some? on-blur) (on-blur)))))]
    (if new-css-system
      [:div {:class (stl/css :text-decoration-options)}
       [:& radio-buttons {:selected text-decoration
                          :on-change handle-change
                          :name "text-decoration-options"}
        [:& radio-button {:value "underline"
                          :type "checkbox"
                          :id "underline-text-decoration"
                          :title (tr "workspace.options.text-options.underline" (sc/get-tooltip :underline))
                          :icon i/text-underlined-refactor}]
        [:& radio-button {:value "line-through"
                          :type "checkbox"
                          :id "line-through-text-decoration"
                          :title (tr "workspace.options.text-options.strikethrough" (sc/get-tooltip :line-through))
                          :icon i/text-stroked-refactor}]]]

      [:div.align-icons
       [:span.tooltip.tooltip-bottom
        {:alt (tr "workspace.options.text-options.none")
         :class (dom/classnames :current (= "none" text-decoration))
         :data-value "none"
         :on-click handle-change}
        i/minus]

       [:span.tooltip.tooltip-bottom
        {:alt (tr "workspace.options.text-options.underline" (sc/get-tooltip :underline))
         :class (dom/classnames :current (= "underline" text-decoration))
         :data-value "underline"
         :on-click handle-change}
        i/underline]

       [:span.tooltip.tooltip-bottom
        {:alt (tr "workspace.options.text-options.strikethrough" (sc/get-tooltip :line-through))
         :class (dom/classnames :current (= "line-through" text-decoration))
         :data-value "line-through"
         :on-click handle-change}
        i/strikethrough]])))

(mf/defc text-menu
  {::mf/wrap [mf/memo]}
  [{:keys [ids type values] :as props}]

  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        file-id        (mf/use-ctx ctx/current-file-id)
        typographies   (mf/deref refs/workspace-file-typography)
        shared-libs    (mf/deref refs/workspace-libraries)
        label          (case type
                         :multiple (tr "workspace.options.text-options.title-selection")
                         :group (tr "workspace.options.text-options.title-group")
                         (tr "workspace.options.text-options.title"))

        state*             (mf/use-state {:main-menu true
                                          :more-options false})
        state              (deref state*)
        main-menu-open?    (:main-menu state)
        more-options-open? (:more-options  state)

        toggle-main-menu
        (mf/use-fn
         (mf/deps main-menu-open?)
         #(swap! state* assoc-in [:main-menu] (not main-menu-open?)))

        toggle-more-options
        (mf/use-fn
         (mf/deps more-options-open?)
         #(swap! state* assoc-in [:more-options] (not more-options-open?)))

        typography-id (:typography-ref-id values)
        typography-file (:typography-ref-file values)

        emit-update!
        (mf/use-fn
         (mf/deps values)
         (fn [ids attrs]
           (st/emit! (dwt/save-font (-> (merge txt/default-text-attrs values attrs)
                                        (select-keys dwt/text-attrs)))
                     (dwt/update-all-attrs ids attrs))))

        on-change
        (mf/use-fn
         (mf/deps ids emit-update!)
         (fn [attrs]
           (emit-update! ids attrs)))

        typography
        (mf/use-memo
         (mf/deps values file-id shared-libs)
         (fn []
           (cond
             (and typography-id
                  (not= typography-id :multiple)
                  (not= typography-file file-id))
             (-> shared-libs
                 (get-in [typography-file :data :typographies typography-id])
                 (assoc :file-id typography-file))

             (and typography-id
                  (not= typography-id :multiple)
                  (= typography-file file-id))
             (get typographies typography-id))))

        on-convert-to-typography
        (fn [_]
          (let [set-values (-> (d/without-nils values)
                               (select-keys
                                (d/concat-vec dwt/text-font-attrs
                                              dwt/text-spacing-attrs
                                              dwt/text-transform-attrs)))
                typography (merge txt/default-typography set-values)
                typography (dwt/generate-typography-name typography)
                id         (uuid/next)]
            (st/emit! (dwl/add-typography (assoc typography :id id) false))
            (emit-update! ids
                          {:typography-ref-id id
                           :typography-ref-file file-id})))

        handle-detach-typography
        (mf/use-fn
         (mf/deps on-change)
         (fn []
           (on-change {:typography-ref-file nil
                       :typography-ref-id nil})))

        handle-change-typography
        (mf/use-fn
         (mf/deps typography file-id)
         (fn [changes]
           (st/emit! (dwl/update-typography (merge typography changes) file-id))))

        multiple? (->> values vals (d/seek #(= % :multiple)))

        opts #js {:ids ids
                  :values values
                  :on-change on-change
                  :show-recent true
                  :on-blur
                  (fn []
                    (ts/schedule
                     100
                     (fn []
                       (when (not= "INPUT" (-> (dom/get-active) (dom/get-tag-name)))
                         (let [node (dom/get-element-by-class "public-DraftEditor-content")]
                           (dom/focus! node))))))}]

    (if new-css-system
      [:div {:class (stl/css :element-set)}
       [:div {:class (stl/css :element-title)}
        [:& title-bar {:collapsable? true
                       :collapsed?   (not main-menu-open?)
                       :on-collapsed toggle-main-menu
                       :title        label
                       :class        (stl/css :title-spacing-text)}
         (when (and (not typography) (not multiple?))
           [:button {:class   (stl/css :add-typography)
                     :on-click on-convert-to-typography}
            i/add-refactor])]]

       (when main-menu-open?
         [:div {:class (stl/css :element-content)}
          (cond
            typography
            [:& typography-entry {:typography typography
                                  :local? (= typography-file file-id)
                                  :file (get shared-libs typography-file)
                                  :on-detach handle-detach-typography
                                  :on-change handle-change-typography}]

            (= typography-id :multiple)
            [:div {:class (stl/css :multiple-typography)}
             [:span {:class (stl/css :multiple-text)} (tr "workspace.libraries.text.multiple-typography")]
             [:div  {:class (stl/css :multiple-typography-button)
                     :on-click handle-detach-typography
                     :title (tr "workspace.libraries.text.multiple-typography-tooltip")}
              i/detach-refactor]]

            :else
            [:> text-options opts])

          [:div {:class (stl/css :text-align-options)}
           [:> text-align-options opts]
           [:> grow-options opts]
           [:button {:class (stl/css :more-options)
                     :on-click toggle-more-options}
            i/menu-refactor]]

          (when more-options-open?
            [:div  {:class (stl/css :text-decoration-options)}
             [:> vertical-align opts]
             [:> text-decoration-options opts]
             [:> text-direction-options opts]])])]


      [:div.element-set
       [:div.element-set-title
        [:span label]
        (when (and (not typography) (not multiple?))
          [:div.add-page {:on-click on-convert-to-typography} i/close])]

       (cond
         typography
         [:& typography-entry {:typography typography
                               :local? (= typography-file file-id)
                               :file (get shared-libs typography-file)
                               :on-detach handle-detach-typography
                               :on-change handle-change-typography}]

         (= typography-id :multiple)
         [:div.multiple-typography
          [:div.multiple-typography-text (tr "workspace.libraries.text.multiple-typography")]
          [:div.multiple-typography-button {:on-click handle-detach-typography
                                            :title (tr "workspace.libraries.text.multiple-typography-tooltip")} i/unchain]]

         :else
         [:> text-options opts])

       [:div.element-set-content

        [:div.row-flex
         [:> text-align-options opts]
         [:> vertical-align opts]]

        [:div.row-flex
         [:> text-decoration-options opts]
         [:> text-direction-options opts]]

        [:div.row-flex
         [:> grow-options opts]
         [:div.align-icons]]]])))
