;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.text
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.data.workspace.texts :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as deprecated-icon]
   [app.main.ui.workspace.sidebar.options.menus.typography :refer [text-options
                                                                   typography-entry]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.text.ui :as txu]
   [app.util.timers :as ts]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(mf/defc text-align-options
  [{:keys [values on-change on-blur] :as props}]
  (let [{:keys [text-align]} values
        handle-change
        (mf/use-fn
         (mf/deps on-change on-blur)
         (fn [value]
           (on-change {:text-align value})
           (when (some? on-blur) (on-blur))))]

    ;; --- Align
    [:div {:class (stl/css :align-options)}
     [:& radio-buttons {:selected text-align
                        :on-change handle-change
                        :name "align-text-options"}
      [:& radio-button {:value "left"
                        :id "text-align-left"
                        :title (tr "workspace.options.text-options.text-align-left")
                        :icon deprecated-icon/text-align-left}]
      [:& radio-button {:value "center"
                        :id "text-align-center"
                        :title (tr "workspace.options.text-options.text-align-center")
                        :icon deprecated-icon/text-align-center}]
      [:& radio-button {:value "right"
                        :id "text-align-right"
                        :title (tr "workspace.options.text-options.text-align-right")
                        :icon deprecated-icon/text-align-right}]
      [:& radio-button {:value "justify"
                        :id "text-align-justify"
                        :title (tr "workspace.options.text-options.text-align-justify")
                        :icon deprecated-icon/text-justify}]]]))

(mf/defc text-direction-options
  [{:keys [values on-change on-blur] :as props}]
  (let [direction     (:text-direction values)
        handle-change
        (mf/use-fn
         (mf/deps on-change on-blur direction)
         (fn [value]
           (let [dir (if (= value direction)
                       "none"
                       value)]
             (on-change {:text-direction dir})
             (when (some? on-blur) (on-blur)))))]

    [:div {:class (stl/css :text-direction-options)}
     [:& radio-buttons {:selected direction
                        :on-change handle-change
                        :name "text-direction-options"}
      [:& radio-button {:value "ltr"
                        :type "checkbox"
                        :id "ltr-text-direction"
                        :title (tr "workspace.options.text-options.direction-ltr")
                        :icon deprecated-icon/text-ltr}]
      [:& radio-button {:value "rtl"
                        :type "checkbox"
                        :id "rtl-text-direction"
                        :title (tr "workspace.options.text-options.direction-rtl")
                        :icon deprecated-icon/text-rtl}]]]))

(mf/defc vertical-align
  [{:keys [values on-change on-blur] :as props}]
  (let [{:keys [vertical-align]} values
        vertical-align (or vertical-align "top")
        handle-change
        (mf/use-fn
         (mf/deps on-change on-blur)
         (fn [value]
           (on-change {:vertical-align value})
           (when (some? on-blur) (on-blur))))]

    [:div {:class (stl/css :vertical-align-options)}
     [:& radio-buttons {:selected vertical-align
                        :on-change handle-change
                        :name "vertical-align-text-options"}
      [:& radio-button {:value "top"
                        :id "vertical-text-align-top"
                        :title (tr "workspace.options.text-options.align-top")
                        :icon deprecated-icon/text-top}]
      [:& radio-button {:value "center"
                        :id "vertical-text-align-center"
                        :title (tr "workspace.options.text-options.align-middle")
                        :icon deprecated-icon/text-middle}]
      [:& radio-button {:value "bottom"
                        :id "vertical-text-align-bottom"
                        :title (tr "workspace.options.text-options.align-bottom")
                        :icon deprecated-icon/text-bottom}]]]))

(mf/defc grow-options
  [{:keys [ids values on-blur] :as props}]
  (let [grow-type (:grow-type values)

        handle-change-grow
        (mf/use-fn
         (mf/deps ids on-blur)
         (fn [value]
           (let [uid (js/Symbol)
                 grow-type (keyword value)]
             (st/emit!
              (dwu/start-undo-transaction uid)
              (dwsh/update-shapes ids #(assoc % :grow-type grow-type)))

             (when (features/active-feature? @st/state "render-wasm/v1")
               (st/emit! (dwt/resize-wasm-text-all ids)))
             ;; We asynchronously commit so every sychronous event is resolved first and inside the transaction
             (ts/schedule #(st/emit! (dwu/commit-undo-transaction uid))))
           (when (some? on-blur) (on-blur))))]

    [:div {:class (stl/css :grow-options)}
     [:& radio-buttons {:selected (d/name grow-type)
                        :on-change handle-change-grow
                        :name "grow-text-options"}
      [:& radio-button {:value "fixed"
                        :id "text-fixed-grow"
                        :title (tr "workspace.options.text-options.grow-fixed")
                        :icon deprecated-icon/text-fixed}]
      [:& radio-button {:value "auto-width"
                        :id "text-auto-width-grow"
                        :title (tr "workspace.options.text-options.grow-auto-width")
                        :icon deprecated-icon/text-auto-width}]
      [:& radio-button {:value "auto-height"
                        :id "text-auto-height-grow"
                        :title (tr "workspace.options.text-options.grow-auto-height")
                        :icon deprecated-icon/text-auto-height}]]]))

(mf/defc text-decoration-options
  [{:keys [values on-change on-blur] :as props}]
  (let [text-decoration (or (:text-decoration values) "none")
        handle-change
        (mf/use-fn
         (mf/deps on-change on-blur text-decoration)
         (fn [value]
           (let [decoration (if (= value text-decoration)
                              "none"
                              value)]
             (on-change {:text-decoration decoration})
             (when (some? on-blur) (on-blur)))))]
    [:div {:class (stl/css :text-decoration-options)}
     [:& radio-buttons {:selected text-decoration
                        :on-change handle-change
                        :name "text-decoration-options"}
      [:& radio-button {:value "underline"
                        :type "checkbox"
                        :id "underline-text-decoration"
                        :title (tr "workspace.options.text-options.underline" (sc/get-tooltip :underline))
                        :icon deprecated-icon/text-underlined}]
      [:& radio-button {:value "line-through"
                        :type "checkbox"
                        :id "line-through-text-decoration"
                        :title (tr "workspace.options.text-options.strikethrough" (sc/get-tooltip :line-through))
                        :icon deprecated-icon/text-stroked}]]]))

(mf/defc text-menu
  {::mf/wrap [mf/memo]}
  [{:keys [ids type values] :as props}]

  (let [file-id        (mf/use-ctx ctx/current-file-id)
        typographies   (mf/deref refs/workspace-file-typography)
        libraries      (mf/deref refs/files)
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

        typography-id      (:typography-ref-id values)
        typography-file-id (:typography-ref-file values)

        emit-update!
        (mf/use-fn
         (mf/deps values)
         (fn [ids attrs]
           (st/emit! (dwt/save-font (-> (merge (txt/get-default-text-attrs) values attrs)
                                        (select-keys txt/text-node-attrs)))
                     (dwt/update-all-attrs ids attrs))))

        on-change
        (mf/use-fn
         (mf/deps ids emit-update!)
         (fn [attrs]
           (emit-update! ids attrs)))

        typography
        (mf/with-memo [values file-id libraries]
          (cond
            (and typography-id
                 (not= typography-id :multiple)
                 (not= typography-file-id file-id))
            (-> libraries
                (get-in [typography-file-id :data :typographies typography-id])
                (assoc :file-id typography-file-id))

            (and typography-id
                 (not= typography-id :multiple)
                 (= typography-file-id file-id))
            (get typographies typography-id)))

        on-convert-to-typography
        (fn [_]
          (let [set-values (-> (d/without-nils values)
                               (select-keys
                                (d/concat-vec txt/text-font-attrs
                                              txt/text-spacing-attrs
                                              txt/text-transform-attrs)))
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

        expand-stream
        (mf/with-memo []
          (->> st/stream
               (rx/filter (ptk/type? :expand-text-more-options))))

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
                         (let [node (txu/get-text-editor-content)]
                           (dom/focus! node))))))}]
    (hooks/use-stream
     expand-stream
     #(swap! state* assoc-in [:more-options] true))

    [:div {:class (stl/css :element-set)}
     [:div {:class (stl/css :element-title)}
      [:> title-bar* {:collapsable  true
                      :collapsed    (not main-menu-open?)
                      :on-collapsed toggle-main-menu
                      :title        label
                      :class        (stl/css :title-spacing-text)}
       (when (and (not typography) (not multiple?))
         [:> icon-button* {:variant "ghost"
                           :aria-label (tr "labels.options")
                           :on-click on-convert-to-typography
                           :icon i/add}])]]

     (when main-menu-open?
       [:div {:class (stl/css :element-content)}
        (cond
          typography
          [:& typography-entry {:file-id typography-file-id
                                :typography typography
                                :local? (= typography-file-id file-id)
                                :on-detach handle-detach-typography
                                :on-change handle-change-typography}]

          (= typography-id :multiple)
          [:div {:class (stl/css :multiple-typography)}
           [:span {:class (stl/css :multiple-text)} (tr "workspace.libraries.text.multiple-typography")]
           [:div  {:class (stl/css :multiple-typography-button)
                   :on-click handle-detach-typography
                   :title (tr "workspace.libraries.text.multiple-typography-tooltip")}
            deprecated-icon/detach]]

          :else
          [:> text-options opts])

        [:div {:class (stl/css :text-align-options)}
         [:> text-align-options opts]
         [:> grow-options opts]
         [:> icon-button* {:variant "ghost"
                           :aria-label (tr "labels.options")
                           :data-testid "text-align-options-button"
                           :on-click toggle-more-options
                           :icon i/menu}]]

        (when more-options-open?
          [:div  {:class (stl/css :text-decoration-options)}
           [:> vertical-align opts]
           [:> text-decoration-options opts]
           [:> text-direction-options opts]])])]))
