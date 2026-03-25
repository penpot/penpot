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
   [app.config :as cf]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.data.workspace.texts :as dwt]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.wasm-text :as dwwt]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.radio-buttons :refer [radio-buttons*]]
   [app.main.ui.ds.controls.shared.options-dropdown :refer [options-dropdown*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.workspace.sidebar.options.menus.token-typography-row :refer [token-typography-row*]]
   [app.main.ui.workspace.sidebar.options.menus.typography :refer [text-options typography-entry]]
   [app.main.ui.workspace.tokens.management.forms.controls.utils :as csu]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [app.util.text.content :as content]
   [app.util.text.ui :as txu]
   [app.util.timers :as ts]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Sub-components
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(mf/defc text-align-options
  [{:keys [values on-change on-blur]}]
  (let [handle-change
        (mf/use-fn
         (mf/deps on-change on-blur)
         (fn [value]
           (on-change {:text-align value})
           (when (some? on-blur)
             (on-blur))))]
    [:div {:class (stl/css :align-options)}
     [:& radio-buttons {:selected (:text-align values)
                        :on-change handle-change
                        :name "align-text-options"}
      [:& radio-button {:value "left"
                        :id "text-align-left"
                        :title (tr "workspace.options.text-options.text-align-left")
                        :icon i/text-align-left}]
      [:& radio-button {:value "center"
                        :id "text-align-center"
                        :title (tr "workspace.options.text-options.text-align-center")
                        :icon i/text-align-center}]
      [:& radio-button {:value "right"
                        :id "text-align-right"
                        :title (tr "workspace.options.text-options.text-align-right")
                        :icon i/text-align-right}]
      [:& radio-button {:value "justify"
                        :id "text-align-justify"
                        :title (tr "workspace.options.text-options.text-align-justify")
                        :icon i/text-justify}]]]))

(mf/defc text-direction-options
  [{:keys [values on-change on-blur]}]
  (let [direction (:text-direction values)
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
                        :icon i/text-ltr}]
      [:& radio-button {:value "rtl"
                        :type "checkbox"
                        :id "rtl-text-direction"
                        :title (tr "workspace.options.text-options.direction-rtl")
                        :icon i/text-rtl}]]]))

(mf/defc vertical-align
  [{:keys [values on-change on-blur]}]
  (let [vertical-align (or (:vertical-align values) "top")
        handle-change
        (mf/use-fn
         (mf/deps on-change on-blur)
         (fn [value]
           (on-change {:vertical-align value})
           (when on-blur (on-blur))))]
    [:div {:class (stl/css :vertical-align-options)}
     [:& radio-buttons {:selected vertical-align
                        :on-change handle-change
                        :name "vertical-align-text-options"}
      [:& radio-button {:value "top"
                        :id "vertical-text-align-top"
                        :title (tr "workspace.options.text-options.align-top")
                        :icon i/text-top}]
      [:& radio-button {:value "center"
                        :id "vertical-text-align-center"
                        :title (tr "workspace.options.text-options.align-middle")
                        :icon i/text-middle}]
      [:& radio-button {:value "bottom"
                        :id "vertical-text-align-bottom"
                        :title (tr "workspace.options.text-options.align-bottom")
                        :icon i/text-bottom}]]]))

(mf/defc grow-options
  [{:keys [ids values on-blur]}]
  (let [grow-type       (:grow-type values)
        editor-instance (mf/deref refs/workspace-editor)
        handle-change
        (mf/use-fn
         (mf/deps ids on-blur editor-instance)
         (fn [value]
           (on-blur)
           (let [uid (js/Symbol)
                 grow-type (keyword value)]
             (st/emit! (dwu/start-undo-transaction uid))
             (when (features/active-feature? @st/state "text-editor/v2")
               (let [content (when editor-instance
                               (content/dom->cljs (dwt/get-editor-root editor-instance)))]
                 (when (some? content)
                   (st/emit! (dwt/v2-update-text-shape-content (first ids) content :finalize? true)))))
             (st/emit! (dwsh/update-shapes ids #(assoc % :grow-type grow-type)))

             (when (features/active-feature? @st/state "render-wasm/v1")
               (st/emit! (dwwt/resize-wasm-text-all ids)))
             ;; We asynchronously commit so every sychronous event is resolved first and inside the transaction
             (ts/schedule #(st/emit! (dwu/commit-undo-transaction uid))))
           (when (some? on-blur) (on-blur))))]

    [:div {:class (stl/css :grow-options)}
     [:& radio-buttons {:selected (d/name grow-type)
                        :on-change handle-change
                        :name "grow-text-options"}
      [:& radio-button {:value "fixed"
                        :id "text-fixed-grow"
                        :title (tr "workspace.options.text-options.grow-fixed")
                        :icon i/text-fixed}]
      [:& radio-button {:value "auto-width"
                        :id "text-auto-width-grow"
                        :title (tr "workspace.options.text-options.grow-auto-width")
                        :icon i/text-auto-width}]
      [:& radio-button {:value "auto-height"
                        :id "text-auto-height-grow"
                        :title (tr "workspace.options.text-options.grow-auto-height")
                        :icon i/text-auto-height}]]]))

(mf/defc text-decoration-options*
  [{:keys [values on-change on-blur token-applied]}]
  (let [token-row    (contains? cf/flags :token-typography-row)
        text-decoration (some-> (:text-decoration values) d/name)
        handle-change
        (mf/use-fn
         (mf/deps on-change on-blur)
         (fn [value]
           (on-change {:text-decoration value})
           (when (some? on-blur)
             (on-blur))))]

    [:div {:class (stl/css :text-decoration-options)}
     [:> radio-buttons* {:selected     (if (= text-decoration "none")
                                         nil
                                         text-decoration)
                         :on-change    handle-change
                         :name         "text-decoration-options"
                         :allow-empty  true
                         :options      [{:value "underline"
                                         :id "underline-text-decoration"
                                         :disabled (and token-row (some? token-applied))
                                         :label (tr "workspace.options.text-options.underline" (sc/get-tooltip :underline))
                                         :icon i/text-underlined}
                                        {:value "line-through"
                                         :id "line-through-text-decoration"
                                         :disabled (and token-row (some? token-applied))
                                         :label (tr "workspace.options.text-options.strikethrough" (sc/get-tooltip :line-through))
                                         :icon i/text-stroked}]}]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Token dropdown navigation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- next-focus-id
  [focusables focused-id direction]
  (let [ids      (vec (map :id focusables))
        idx      (.indexOf (clj->js ids) focused-id)
        idx      (if (= idx -1) -1 idx)
        next-idx (case direction
                   :down (mod (inc idx) (count ids))
                   :up   (mod (dec (if (= idx -1) 0 idx)) (count ids)))]
    (nth ids next-idx nil)))

(defn use-dropdown-navigation
  [{:keys [is-open is-open* options nodes-ref on-enter]}]
  (let [focused-id* (mf/use-state nil)
        focused-id  (deref focused-id*)

        on-key-down
        (mf/use-fn
         (mf/deps is-open focused-id)
         (fn [event]
           (let [options (if (delay? options) @options options)]
             (cond
               (kbd/down-arrow? event)
               (do
                 (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (when is-open
                   (reset! focused-id* (next-focus-id (csu/focusable-options options) focused-id :down))))

               (kbd/up-arrow? event)
               (do
                 (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (when is-open
                   (reset! focused-id* (next-focus-id (csu/focusable-options options) focused-id :up))))

               (kbd/enter? event)
               (when (and is-open focused-id)
                 (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (on-enter focused-id))

               (or (kbd/esc? event) (kbd/tab? event))
               (do
                 (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (reset! is-open* false)
                 (reset! focused-id* nil))))))]

    (mf/with-effect [is-open options]
      (when is-open
        (let [opts       (if (delay? options) @options options)
              focusables (csu/focusable-options opts)
              ids        (set (map :id focusables))]
          (when (and (seq focusables) (not (contains? ids focused-id)))
            (reset! focused-id* (:id (first focusables)))))))

    (mf/with-effect [focused-id nodes-ref]
      (when focused-id
        (let [node (obj/get (mf/ref-val nodes-ref) focused-id)]
          (when node
            (dom/scroll-into-view-if-needed! node {:block "nearest" :inline "nearest"})))))

    {:focused-id  focused-id
     :on-key-down on-key-down}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- get-option-by-name [options name]
  (let [options (if (delay? options) (deref options) options)]
    (d/seek #(= name (get % :name)) options)))

(defn- resolve-delay [tokens]
  (if (delay? tokens) @tokens tokens))

(defn- find-token-by-id [tokens id]
  (->> (:typography tokens)
       (d/seek #(= (:id %) (uuid/uuid id)))))

(defn- check-props [n-props o-props]
  (and (identical? (unchecked-get n-props "ids")
                   (unchecked-get o-props "ids"))
       (identical? (unchecked-get n-props "appliedTokens")
                   (unchecked-get o-props "appliedTokens"))
       (identical? (unchecked-get n-props "values")
                   (unchecked-get o-props "values"))
       (identical? (:typography-ref-id (unchecked-get n-props "values"))
                   (:typography-ref-id (unchecked-get o-props "values")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Main component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(mf/defc text-menu*
  {::mf/wrap [#(mf/memo' % check-props)]}
  [{:keys [ids type values applied-tokens]}]

  (let [file-id      (mf/use-ctx ctx/current-file-id)
        typographies (mf/deref refs/workspace-file-typography)
        libraries    (mf/deref refs/files)
        token-row    (contains? cf/flags :token-typography-row)

        ;; --- UI state
        menu-state*         (mf/use-state {:main-menu true
                                           :more-options false})
        menu-state          (deref menu-state*)
        main-menu-open?     (:main-menu menu-state)
        more-options-open?  (:more-options menu-state)

        token-dropdown-open* (mf/use-state false)
        token-dropdown-open? (deref token-dropdown-open*)

        ;; --- Applied token
        applied-token-name   (:typography applied-tokens)
        applied-token-name*  (mf/use-state applied-token-name)
        current-token-name   (deref applied-token-name*)

        ;; --- Available tokens
        active-tokens     (mf/use-ctx ctx/active-tokens-by-type)
        typography-tokens (mf/with-memo [active-tokens] (csu/filter-tokens-for-input active-tokens :typography))

        ;; --- Dropdown
        listbox-id    (mf/use-id)
        nodes-ref     (mf/use-ref nil)
        dropdown-ref  (mf/use-ref nil)

        dropdown-options
        (mf/with-memo [typography-tokens]
          (csu/get-token-dropdown-options typography-tokens nil))

        selected-token-id*
        (mf/use-state #(when current-token-name
                         (:id (get-option-by-name dropdown-options current-token-name))))
        selected-token-id (deref selected-token-id*)

        ;; --- Typography
        typography-id      (:typography-ref-id values)
        typography-file-id (:typography-ref-file values)

        typography
        (mf/with-memo [typography-id typography-file-id file-id libraries]
          (cond
            (and typography-id
                 (not= typography-id :multiple)
                 (not= typography-file-id file-id))
            (-> (get-in libraries [typography-file-id :data :typographies typography-id])
                (assoc :file-id typography-file-id))

            (and typography-id
                 (not= typography-id :multiple)
                 (= typography-file-id file-id))
            (get typographies typography-id)))

        ;; --- Helpers
        multiple?       (->> values vals (d/seek #(= % :multiple)))

        apply-token!
        (mf/use-fn
         (mf/deps ids typography-tokens)
         (fn [id]
           (let [token (find-token-by-id (resolve-delay typography-tokens) id)]
             (reset! selected-token-id* id)
             (reset! token-dropdown-open* false)
             (st/emit!
              (dwta/apply-token {:shape-ids         ids
                                 :attributes        #{:typography}
                                 :token             token
                                 :on-update-shape   dwta/update-typography})))))
        label          (case type
                         :multiple (tr "workspace.options.text-options.title-selection")
                         :group (tr "workspace.options.text-options.title-group")
                         (tr "workspace.options.text-options.title"))
        set-option-ref
        (mf/use-fn
         (fn [node]
           (let [state (d/nilv (mf/ref-val nodes-ref) #js {})
                 id    (dom/get-data node "id")]
             (mf/set-ref-val! nodes-ref (obj/set! state id node))
             (fn []
               (let [state (d/nilv (mf/ref-val nodes-ref) #js {})]
                 (mf/set-ref-val! nodes-ref (obj/unset! state id)))))))

        {:keys [focused-id on-key-down]}
        (use-dropdown-navigation
         {:is-open   token-dropdown-open?
          :is-open*  token-dropdown-open*
          :options   dropdown-options
          :nodes-ref nodes-ref
          :on-enter  apply-token!})

        ;; --- Toggles
        toggle-main-menu
        (mf/use-fn
         (mf/deps main-menu-open?)
         #(swap! menu-state* update :main-menu not))

        toggle-more-options
        (mf/use-fn
         (mf/deps more-options-open?)
         #(swap! menu-state* update :more-options not))

        toggle-token-dropdown
        (mf/use-fn
         #(swap! token-dropdown-open* not))

        ;; --- Event handlers
        on-option-click
        (mf/use-fn
         (mf/deps apply-token!)
         (fn [event]
           (dom/stop-propagation event)
           (let [id (dom/get-data (dom/get-current-target event) "id")]
             (apply-token! id))))

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

        on-convert-to-typography
        (mf/use-fn
         (mf/deps values ids file-id emit-update!)
         (fn [_]
           (let [set-values (-> (d/without-nils values)
                                (select-keys (d/concat-vec txt/text-font-attrs
                                                           txt/text-spacing-attrs
                                                           txt/text-transform-attrs)))
                 typography (-> (merge txt/default-typography set-values)
                                (dwt/generate-typography-name))
                 id         (uuid/next)]
             (st/emit! (dwl/add-typography (assoc typography :id id) false))
             (emit-update! ids {:typography-ref-id id :typography-ref-file file-id}))))

        handle-detach-typography
        (mf/use-fn
         (mf/deps on-change)
         #(on-change {:typography-ref-file nil :typography-ref-id nil}))

        handle-change-typography
        (mf/use-fn
         (mf/deps typography file-id)
         (fn [changes]
           (st/emit! (dwl/update-typography (merge typography changes) file-id))))

        detach-token
        (mf/use-fn
         (fn [token-name]
           (st/emit! (dwta/unapply-token {:token-name  token-name
                                          :attributes  #{:typography}
                                          :shape-ids   ids}))))

        expand-stream
        (mf/with-memo []
          (->> st/stream (rx/filter (ptk/type? :expand-text-more-options))))

        opts #js {:ids       ids
                  :values    values
                  :on-change on-change
                  :show-recent true
                  :on-blur
                  (fn []
                    (ts/schedule
                     100
                     (fn []
                       (when (not= "INPUT" (-> (dom/get-active) dom/get-tag-name))
                         (dom/focus! (txu/get-text-editor-content))))))}]

    (hooks/use-stream
     expand-stream
     #(swap! menu-state* assoc :more-options true))

    (mf/with-effect [applied-token-name]
      (reset! current-token-name* applied-token-name))

    (mf/with-effect [applied-token-name dropdown-options]
      (reset! selected-token-id*
              (when applied-token-name
                (:id (get-option-by-name dropdown-options applied-token-name)))))

    (mf/with-effect [token-dropdown-open?]
      (when token-dropdown-open?
        (ts/schedule 0 #(some-> (mf/ref-val dropdown-ref) dom/focus!))))

    [:section {:class      (stl/css :element-set)
               :aria-label (tr "workspace.options.text-options.text-section")}
     [:div {:class (stl/css :element-title)}
      [:> title-bar* {:collapsable  true
                      :collapsed    (not main-menu-open?)
                      :on-collapsed toggle-main-menu
                      :title        label
                      :class        (stl/css :title-spacing-text)}
       [:*
        (when (and token-row (some? typography-tokens) (not typography))
          [:> icon-button* {:variant           "ghost"
                            :aria-label        (tr "ds.inputs.numeric-input.open-token-list-dropdown")
                            :on-click          toggle-token-dropdown
                            :tooltip-placement "top-left"
                            :icon              i/tokens}])
        (when (and (not typography) (not multiple?))
          [:> icon-button* {:variant           "ghost"
                            :aria-label        (tr "workspace.options.convert-to-typography")
                            :on-click          on-convert-to-typography
                            :tooltip-placement "top-left"
                            :icon              i/add}])]]]

     (when main-menu-open?
       [:div {:class (stl/css :element-content)}
        (cond
          (and token-row current-token-name)
          [:> token-typography-row* {:token-name    current-token-name
                                     :detach-token  detach-token
                                     :active-tokens (resolve-delay typography-tokens)}]

          typography
          [:& typography-entry {:file-id    typography-file-id
                                :typography typography
                                :local?     (= typography-file-id file-id)
                                :on-detach  handle-detach-typography
                                :on-change  handle-change-typography}]

          (= typography-id :multiple)
          [:div {:class (stl/css :multiple-typography)}
           [:span {:class (stl/css :multiple-text)} (tr "workspace.libraries.text.multiple-typography")]
           [:> icon-button* {:variant    "ghost"
                             :aria-label (tr "workspace.libraries.text.multiple-typography-tooltip")
                             :on-click   handle-detach-typography
                             :icon       i/detach}]]

          :else
          [:> text-options opts])

        [:div {:class (stl/css :text-align-options)}
         [:> text-align-options opts]
         [:> grow-options opts]
         [:> icon-button* {:variant     "ghost"
                           :aria-label  (tr "labels.options")
                           :data-testid "text-align-options-button"
                           :on-click    toggle-more-options
                           :icon        i/menu}]]

        (when more-options-open?
          [:div {:class (stl/css :text-decoration-options)}
           [:> vertical-align opts]
           [:> text-decoration-options* {:values    values
                                         :on-change on-change
                                         :token-applied current-token-name
                                         :on-blur
                                         (fn []
                                           (ts/schedule
                                            100
                                            (fn []
                                              (when (not= "INPUT" (-> (dom/get-active) dom/get-tag-name))
                                                (dom/focus! (txu/get-text-editor-content))))))}]
           [:> text-direction-options opts]])])

     (when (and token-row token-dropdown-open?)
       (let [options (resolve-delay dropdown-options)]
         [:div {:on-key-down on-key-down
                :ref         dropdown-ref
                :tab-index   0}
          [:> options-dropdown* {:on-click      on-option-click
                                 :id            listbox-id
                                 :options       options
                                 :selected      selected-token-id
                                 :focused       focused-id
                                 :align         "right"
                                 :empty-to-end  false
                                 :ref           set-option-ref}]]))]))