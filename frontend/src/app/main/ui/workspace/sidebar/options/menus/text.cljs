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
   [app.main.ui.workspace.sidebar.options.menus.typography :refer [text-options
                                                                   typography-entry]]
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
                        :icon i/text-ltr}]
      [:& radio-button {:value "rtl"
                        :type "checkbox"
                        :id "rtl-text-direction"
                        :title (tr "workspace.options.text-options.direction-rtl")
                        :icon i/text-rtl}]]]))

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
  [{:keys [ids values on-blur] :as props}]
  (let [grow-type (:grow-type values)

        editor-instance (mf/deref refs/workspace-editor)

        handle-change-grow
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
                        :on-change handle-change-grow
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

(mf/defc text-decoration-options
  [{:keys [values on-change on-blur] :as props}]
  (let [text-decoration (or (d/name (:text-decoration values)) "none")

        handle-change
        (mf/use-fn
         (mf/deps on-change on-blur)
         (fn [value]
           (let [decoration (or value "none")]
             (on-change {:text-decoration decoration})
             (when (some? on-blur)
               (on-blur)))))]

    [:div {:class (stl/css :text-decoration-options)}
     [:> radio-buttons* {:selected text-decoration
                         :on-change handle-change
                         :name "text-decoration-options"
                         :allow-empty true
                         :options [{:value "underline"
                                    :id "underline-text-decoration"
                                    :label (tr "workspace.options.text-options.underline" (sc/get-tooltip :underline))
                                    :icon i/text-underlined}
                                   {:value "line-through"
                                    :id "line-through-text-decoration"
                                    :label (tr "workspace.options.text-options.strikethrough" (sc/get-tooltip :line-through))
                                    :icon i/text-stroked}]}]]))

(defn- get-option-by-name
  [options name]
  (let [options (if (delay? options) (deref options) options)]
    (d/seek #(= name (get % :name)) options)))

(defn- check-props
  [n-props o-props]
  (let [ids-same?    (identical? (unchecked-get n-props "ids")
                                 (unchecked-get o-props "ids"))
        tokens-same? (identical? (unchecked-get o-props "appliedTokens")
                                 (unchecked-get n-props "appliedTokens"))
        o-vals  (unchecked-get o-props "values")
        n-vals  (unchecked-get n-props "values")
        o-typography-id      (:typography-ref-id o-vals)
        n-typography-id      (:typography-ref-id n-vals)
        typo-same? (identical? o-typography-id n-typography-id)
        result (and ids-same? tokens-same? typo-same?)]
    result))
(defn- focusable-option?
  [option]
  (and (:id option)
       (not= :group (:type option))
       (not= :separator (:type option))))

(defn- first-focusable-id
  [options]
  (some #(when (focusable-option? %) (:id %)) options))

(defn next-focus-id
  [focusables focused-id direction]
  (let [ids     (vec (map :id focusables))
        idx     (.indexOf (clj->js ids) focused-id)
        idx     (if (= idx -1) -1 idx)
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
           (let [up?    (kbd/up-arrow? event)
                 down?  (kbd/down-arrow? event)
                 enter? (kbd/enter? event)
                 esc?   (kbd/esc? event)
                 tab?   (kbd/tab? event)
                 options (if (delay? options) @options options)]

             (cond
               down?
               (do
                 (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (when is-open
                   (let [focusables (csu/focusable-options options)
                         next-id    (next-focus-id focusables focused-id :down)]
                     (reset! focused-id* next-id))))

               up?
               (do
                 (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (when is-open
                   (let [focusables (csu/focusable-options options)
                         next-id    (next-focus-id focusables focused-id :up)]
                     (reset! focused-id* next-id))))

               enter?
               (when (and is-open focused-id)
                 (dom/prevent-default event)
                 (on-enter focused-id))

               (or esc? tab?)
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
          (when (and (seq focusables)
                     (not (contains? ids focused-id)))
            (reset! focused-id* (:id (first focusables)))))))

    (mf/with-effect [focused-id nodes-ref]
      (when focused-id
        (let [nodes (mf/ref-val nodes-ref)
              node  (obj/get nodes focused-id)]
          (when node
            (dom/scroll-into-view-if-needed!
             node {:block "nearest"
                   :inline "nearest"})))))

    {:focused-id focused-id
     :on-key-down on-key-down}))

(mf/defc text-menu*
  {::mf/wrap [#(mf/memo' % check-props)]}
  [{:keys [ids type values applied-tokens] :rest props}]

  (let [file-id        (mf/use-ctx ctx/current-file-id)
        typographies   (mf/deref refs/workspace-file-typography)
        libraries      (mf/deref refs/files)
        token-row      (contains? cf/flags :token-typography-row)

        state*             (mf/use-state {:main-menu true
                                          :more-options false})
        state              (deref state*)

        main-menu-open?    (:main-menu state)
        more-options-open? (:more-options  state)

        open-tokens* (mf/use-state false)
        open-tokens? (deref open-tokens*)

        applied-token (:typography applied-tokens)

        token-applied-name*  (mf/use-state applied-token)
        token-applied-name   (deref token-applied-name*)

        tokens
        (mf/use-ctx ctx/active-tokens-by-type)

        tokens
        (mf/with-memo [tokens]
          (csu/filter-tokens-for-input tokens :typography))

        listbox-id           (mf/use-id)
        nodes-ref            (mf/use-ref nil)
        dropdown-ref (mf/use-ref nil)

        detach-token
        (mf/use-fn
         (mf/deps)
         (fn [token-name]
           (st/emit! (dwta/unapply-token {:token-name token-name
                                          :attributes #{:typography}
                                          :shape-ids ids}))))

        set-option-ref
        (mf/use-fn
         (fn [node]
           (let [state (mf/ref-val nodes-ref)
                 state (d/nilv state #js {})
                 id    (dom/get-data node "id")
                 state (obj/set! state id node)]
             (mf/set-ref-val! nodes-ref state)
             (fn []
               (let [state (mf/ref-val nodes-ref)
                     state (d/nilv state #js {})
                     id    (dom/get-data node "id")
                     state (obj/unset! state id)]
                 (mf/set-ref-val! nodes-ref state))))))

        dropdown-options
        (mf/with-memo [tokens]
          (csu/get-token-dropdown-options tokens nil))

        selected-id*
        (mf/use-state
         (fn []
           (if token-applied-name
             (:id (get-option-by-name dropdown-options token-applied-name))
             nil)))
        selected-id (deref selected-id*)

        label          (case type
                         :multiple (tr "workspace.options.text-options.title-selection")
                         :group (tr "workspace.options.text-options.title-group")
                         (tr "workspace.options.text-options.title"))

        {:keys [focused-id on-key-down]}
        (use-dropdown-navigation
         {:is-open   open-tokens?
          :is-open*  open-tokens*
          :options   dropdown-options
          :nodes-ref nodes-ref
          :on-enter  (fn [id]
                       (let [token (->> (:typography @tokens)
                                        (d/seek #(= (:id %) (uuid/uuid id))))]
                         (reset! selected-id* id)
                         (reset! open-tokens* false)
                         (st/emit!
                          (dwta/apply-token {:shape-ids ids
                                             :attributes #{:typography}
                                             :token token
                                             :on-update-shape dwta/update-typography}))))})

        toggle-main-menu
        (mf/use-fn
         (mf/deps main-menu-open?)
         #(swap! state* assoc-in [:main-menu] (not main-menu-open?)))

        toggle-more-options
        (mf/use-fn
         (mf/deps more-options-open?)
         #(swap! state* assoc-in [:more-options] (not more-options-open?)))

        toggle-tokens-dropdown
        (mf/use-fn
         #(swap! open-tokens* not))


        on-option-click
        (mf/use-fn
         (mf/deps ids tokens)
         (fn [event]
           (dom/stop-propagation event)
           (let [node  (dom/get-current-target event)
                 id    (dom/get-data node "id")
                 token (->> (:typography @tokens)
                            (d/seek #(= (:id %) (uuid/uuid id))))]
             (reset! selected-id* id)
             (swap! state* assoc :open-tokens false)
             (reset! open-tokens* false)
             (st/emit!
              (dwta/apply-token {:shape-ids ids
                                 :attributes #{:typography}
                                 :token token
                                 :on-update-shape dwta/update-typography})))))

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


        typography-id      (:typography-ref-id values)
        typography-file-id (:typography-ref-file values)

        typography
        (mf/with-memo [typography-id typography-file-id file-id libraries]
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

    (mf/with-effect [applied-token]
      (reset! token-applied-name* applied-token))

    (mf/with-effect [applied-token dropdown-options]
      (if applied-token
        (reset! selected-id* (:id (get-option-by-name dropdown-options applied-token)))
        (reset! selected-id* nil)))

    (mf/with-effect [open-tokens?]
      (when open-tokens?
        (ts/schedule 0 #(when-let [node (mf/ref-val dropdown-ref)]
                          (dom/focus! node)))))

    [:section {:class (stl/css :element-set)
               ;; TODO: Add translation
               :aria-label "Text section"}
     [:div {:class (stl/css :element-title)}
      [:> title-bar* {:collapsable  true
                      :collapsed    (not main-menu-open?)
                      :on-collapsed toggle-main-menu
                      :title        label
                      :class        (stl/css :title-spacing-text)}
       [:*
        ;; TODO: Check multiple, and with typography
        (when (and token-row (some? tokens) (not typography))
          [:> icon-button* {:variant "ghost"
                            ;; TODO: ask for confimation to this copy and add translation
                            :aria-label "Toggle token list"
                            :on-click toggle-tokens-dropdown
                            :tooltip-placement "top-left"
                            :icon i/tokens}])
        (when (and (not typography) (not multiple?))
          [:> icon-button* {:variant "ghost"
                            ;; TODO: ask for confimation to this copy and add translation
                            :aria-label "Convert to typography"
                            :on-click on-convert-to-typography
                            :tooltip-placement "top-left"
                            :icon i/add}])]]]

     (when main-menu-open?
       [:div {:class (stl/css :element-content)}
        (cond
          (and token-row token-applied-name)
          [:> token-typography-row* {:token-name token-applied-name :detach-token detach-token :tokens @tokens}]

          typography
          [:& typography-entry {:file-id typography-file-id
                                :typography typography
                                :local? (= typography-file-id file-id)
                                :on-detach handle-detach-typography
                                :on-change handle-change-typography}]

          (= typography-id :multiple)
          [:div {:class (stl/css :multiple-typography)}
           [:span {:class (stl/css :multiple-text)} (tr "workspace.libraries.text.multiple-typography")]
           [:> icon-button* {:variant "ghost"
                             :aria-label (tr "workspace.libraries.text.multiple-typography-tooltip")
                             :on-click handle-detach-typography
                             :icon i/detach}]]

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
           [:> text-direction-options opts]])])
     ;; TODO; add search bar on dropdown
     (when (and token-row open-tokens?)
       (let [options (if (delay? dropdown-options) @dropdown-options dropdown-options)]
         [:div {:on-key-down on-key-down
                :ref dropdown-ref
                :tab-index 0}
          [:> options-dropdown* {:on-click on-option-click
                                 :id listbox-id
                                 :options options
                                 :selected selected-id
                                 :focused focused-id
                                 :align "right"
                                 :empty-to-end false
                                 :ref set-option-ref}]]))]))
