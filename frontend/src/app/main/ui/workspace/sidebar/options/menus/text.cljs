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
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.radio-buttons :refer [radio-buttons*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as deprecated-icon]
   [app.main.ui.workspace.sidebar.options.menus.typography :refer [text-options*
                                                                   typography-entry*]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.text.ui :as txu]
   [app.util.timers :as ts]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(mf/defc text-align-options*
  [{:keys [values on-change on-blur]}]
  (let [{:keys [text-align]} values

        handle-change
        (mf/use-fn
         (mf/deps on-change on-blur)
         (fn [value]
           (on-change {:text-align value})
           (when (some? on-blur)
             (on-blur))))]

    [:> radio-buttons* {:class (stl/css :align-options)
                        :selected text-align
                        :on-change handle-change
                        :name "align-text-options"
                        :options [{:id "text-align-left"
                                   :icon i/text-align-left
                                   :label (tr "workspace.options.text-options.text-align-left")
                                   :value "left"}
                                  {:id "text-align-center"
                                   :icon i/text-align-center
                                   :label (tr "workspace.options.text-options.text-align-center")
                                   :value "center"}
                                  {:id "text-align-right"
                                   :icon i/text-align-right
                                   :label (tr "workspace.options.text-options.text-align-right")
                                   :value "right"}
                                  {:id "text-align-justify"
                                   :icon i/text-justify
                                   :label (tr "workspace.options.text-options.text-align-justify")
                                   :value "justify"}]}]))

(mf/defc text-direction-options*
  [{:keys [values on-change on-blur]}]
  (let [direction     (:text-direction values)

        handle-change
        (mf/use-fn
         (mf/deps on-change on-blur direction)
         (fn [value]
           (let [dir (if (= value direction) "none" value)]
             (on-change {:text-direction dir})
             (when (some? on-blur)
               (on-blur)))))]

    [:> radio-buttons* {:class (stl/css :text-direction-options)
                        :selected direction
                        :on-change handle-change
                        :allow-empty true
                        :name "text-direction-options"
                        :options [{:id "ltr-text-direction"
                                   :icon i/text-ltr
                                   :label (tr "workspace.options.text-options.direction-ltr")
                                   :value "ltr"}
                                  {:id "rtl-text-direction"
                                   :icon i/text-rtl
                                   :label (tr "workspace.options.text-options.direction-rtl")
                                   :value "rtl"}]}]))

(mf/defc vertical-align*
  [{:keys [values on-change on-blur]}]
  (let [{:keys [vertical-align]} values

        vertical-align (or vertical-align "top")

        handle-change
        (mf/use-fn
         (mf/deps on-change on-blur)
         (fn [value]
           (on-change {:vertical-align value})
           (when (some? on-blur)
             (on-blur))))]

    [:> radio-buttons* {:class (stl/css :vertical-align-options)
                        :selected vertical-align
                        :on-change handle-change
                        :name "vertical-align-text-options"
                        :options [{:id "vertical-text-align-top"
                                   :icon i/text-top
                                   :label (tr "workspace.options.text-options.align-top")
                                   :value "top"}
                                  {:id "vertical-text-align-center"
                                   :icon i/text-middle
                                   :label (tr "workspace.options.text-options.align-middle")
                                   :value "center"}
                                  {:id "vertical-text-align-bottom"
                                   :icon i/text-bottom
                                   :label (tr "workspace.options.text-options.align-bottom")
                                   :value "bottom"}]}]))

(mf/defc grow-options*
  [{:keys [ids values on-blur]}]
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
           (when (some? on-blur)
             (on-blur))))]

    [:> radio-buttons* {:class (stl/css :grow-options)
                        :selected (d/name grow-type)
                        :on-change handle-change-grow
                        :name "grow-text-options"
                        :options [{:id "text-fixed-grow"
                                   :icon i/text-fixed
                                   :label (tr "workspace.options.text-options.grow-fixed")
                                   :value "fixed"}
                                  {:id "text-auto-width-grow"
                                   :icon i/text-auto-width
                                   :label (tr "workspace.options.text-options.grow-auto-width")
                                   :value "auto-width"}
                                  {:id "text-auto-height-grow"
                                   :icon i/text-auto-height
                                   :label (tr "workspace.options.text-options.grow-auto-height")
                                   :value "auto-height"}]}]))

(mf/defc text-decoration-options*
  [{:keys [values on-change on-blur]}]
  (let [text-decoration (or (:text-decoration values) "none")

        handle-change
        (mf/use-fn
         (mf/deps on-change on-blur text-decoration)
         (fn [value]
           (let [decoration (if (= value text-decoration) "none" value)]
             (on-change {:text-decoration decoration})
             (when (some? on-blur)
               (on-blur)))))]

    [:> radio-buttons* {:class (stl/css :text-decoration-options)
                        :selected text-decoration
                        :on-change handle-change
                        :name "grow-text-options"
                        :allow-empty true
                        :options [{:id "underline-text-decoration"
                                   :icon i/text-underlined
                                   :label (tr "workspace.options.text-options.underline" (sc/get-tooltip :underline))
                                   :value "underline"}
                                  {:id "line-through-text-decoration"
                                   :icon i/text-stroked
                                   :label (tr "workspace.options.text-options.strikethrough" (sc/get-tooltip :line-through))
                                   :value "line-through"}]}]))

(mf/defc text-menu*
  {::mf/wrap [mf/memo]}
  [{:keys [ids type values]}]

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

        props
        (mf/props {:ids ids
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
                            (dom/focus! node))))))})]
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
          [:> typography-entry* {:file-id typography-file-id
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
          [:> text-options* props])

        [:div {:class (stl/css :text-align-options)}
         [:> text-align-options* props]
         [:> grow-options* props]
         [:> icon-button* {:variant "ghost"
                           :aria-label (tr "labels.options")
                           :aria-pressed more-options-open?
                           :data-testid "text-align-options-button"
                           :on-click toggle-more-options
                           :icon i/menu}]]

        (when more-options-open?
          [:div {:class (stl/css :text-decoration-options)}
           [:> vertical-align* props]
           [:> text-decoration-options* props]
           [:> text-direction-options* props]])])]))
