;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.ds.controls.select
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.ui.ds.controls.shared.options-dropdown :refer [options-dropdown* schema:option]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.tooltip.tooltip :refer [tooltip*]]
   [app.main.ui.hooks :as hooks]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [app.util.timers :as timers]
   [clojure.string :as str]
   [rumext.v2 :as mf]
   [rumext.v2.util :as mfu]))

(defn get-option
  [options id]
  (let [options (if (delay? options) @options options)]
    (or (d/seek #(= id (get % :id)) options)
        (when (seq options)
          (nth options 0)))))

(defn- get-selected-option-id
  [options default]
  (let [option (get-option options default)]
    (get option :id)))

;; Also used in combobox
(defn handle-focus-change
  [options focused* new-index nodes]
  (let [option (get options new-index)
        id     (get option :id)
        node   (obj/get nodes id)]
    (reset! focused* id)
    (dom/scroll-into-view-if-needed! node)))

(defn- handle-selection
  [focused* selected* open*]
  (when-let [focused (deref focused*)]
    (reset! selected* focused))
  (reset! open* false)
  (reset! focused* nil))

(def ^:private schema:select
  [:map
   [:options [:vector {:min 1} schema:option]]
   [:class {:optional true} :string]
   [:wrapper-class {:optional true} :string]
   [:disabled {:optional true} :boolean]
   [:default-selected {:optional true} :string]
   [:empty-to-end {:optional true} [:maybe :boolean]]
   [:on-change {:optional true} fn?]
   [:dropdown-alignment {:optional true} [:maybe [:enum :left :right]]]
   [:variant {:optional true} [:maybe [:enum "default" "ghost" "icon-only"]]]
   [:has-portal {:optional true} :boolean]])

(mf/defc select*
  {::mf/schema schema:select}
  [{:keys [options class disabled default-selected empty-to-end on-change variant wrapper-class dropdown-alignment has-portal] :rest props}]
  (let [;; NOTE: we use mfu/bean here for transparently handle
        ;; options provide as clojure data structures or javascript
        ;; plain objects and lists.
        options      (if (array? options)
                       (mfu/bean options)
                       options)

        variant      (d/nilv variant "default")

        empty-to-end (d/nilv empty-to-end false)
        is-open*     (mf/use-state false)
        is-open      (deref is-open*)

        selected-id* (mf/use-state  #(get-selected-option-id options default-selected))
        selected-id  (deref selected-id*)

        focused-id*  (mf/use-state nil)
        focused-id   (deref focused-id*)

        listbox-id   (mf/use-id)

        nodes-ref    (mf/use-ref nil)
        options-ref  (mf/use-ref nil)
        select-ref   (mf/use-ref nil)

        container    (hooks/use-portal-container :popup)
        dropdown-wrapper-ref (mf/use-ref nil)

        empty-selected-id?
        (str/blank? selected-id)

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

        on-option-click
        (mf/use-fn
         (mf/deps on-change)
         (fn [event]
           (dom/stop-propagation event)
           (let [node  (dom/get-current-target event)
                 id    (dom/get-data node "id")]
             (reset! selected-id* id)
             (reset! focused-id* nil)
             (reset! is-open* false)
             (when (fn? on-change)
               (on-change id)))))

        on-click
        (mf/use-fn
         (mf/deps disabled)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (when-not disabled
             (swap! is-open* not))))

        on-blur
        (mf/use-fn
         (fn [event]
           (let [target      (dom/get-related-target event)
                 select-node (mf/ref-val select-ref)]
             (when-not (dom/is-child? select-node target)
               (reset! focused-id* nil)
               (reset! is-open* false)))))

        on-button-key-down
        (mf/use-fn
         (mf/deps focused-id disabled)
         (fn [event]
           (dom/stop-propagation event)
           (when-not disabled
             (let [options (mf/ref-val options-ref)
                   len     (count options)
                   index   (d/index-of-pred options #(= focused-id (get % :id)))
                   nodes   (mf/ref-val nodes-ref)]
               (cond
                 (kbd/home? event)
                 (handle-focus-change options focused-id* 0 nodes)

                 (kbd/up-arrow? event)
                 (handle-focus-change options focused-id* (mod (- index 1) len) nodes)

                 (kbd/down-arrow? event)
                 (handle-focus-change options focused-id* (mod (+ index 1) len) nodes)

                 (or (kbd/space? event)
                     (kbd/enter? event))
                 (when (deref is-open*)
                   (dom/prevent-default event)
                   (handle-selection focused-id* selected-id* is-open*)
                   (when (and (fn? on-change)
                              (some? focused-id))
                     (on-change focused-id)))

                 (kbd/esc? event)
                 (do (reset! is-open* false)
                     (reset! focused-id* nil)))))))

        props
        (mf/spread-props props {:class [class (stl/css :select) (stl/css-case :variant-ghost (= variant "ghost"))]
                                :role "combobox"
                                :aria-controls listbox-id
                                :aria-haspopup "listbox"
                                :aria-activedescendant focused-id
                                :aria-expanded is-open
                                :on-key-down on-button-key-down
                                :disabled disabled
                                :on-click on-click})

        selected-option
        (mf/with-memo [options selected-id]
          (when (d/not-empty? options)
            (get-option options selected-id)))

        label
        (get selected-option :label)

        icon
        (get selected-option :icon)

        has-icon?
        (some? icon)

        dimmed?
        (:dimmed selected-option)

        icon-ref (mf/use-ref nil)
        icon-id (mf/use-id)]

    (mf/with-effect [options]
      (mf/set-ref-val! options-ref options))

    (mf/with-effect [default-selected options]
      (reset! selected-id*
              (get-selected-option-id options default-selected)))

    ;; Portal mode: click-outside + floating positioning
    (mf/with-effect [is-open has-portal]
      (when (and is-open has-portal)
        (let [handler
              (fn [event]
                (let [wrapper-node  (mf/ref-val select-ref)
                      dropdown-node (mf/ref-val dropdown-wrapper-ref)
                      target        (dom/get-target event)]
                  (when (and wrapper-node dropdown-node
                             (not (dom/child? target wrapper-node))
                             (not (dom/child? target dropdown-node)))
                    (reset! is-open* false)
                    (reset! focused-id* nil))))

              calculate
              (fn []
                (timers/raf
                 (fn []
                   (when-let [select-node (mf/ref-val select-ref)]
                     (when-let [dropdown-node (mf/ref-val dropdown-wrapper-ref)]
                       (let [select-rect   (dom/get-bounding-rect select-node)
                             dropdown-rect (dom/get-bounding-rect dropdown-node)
                             window-height (.-innerHeight js/window)
                             space-below   (- window-height (:bottom select-rect))
                             open-up?      (> (:height dropdown-rect) space-below)]
                         (if open-up?
                           (let [bottom (+ (- window-height (:top select-rect)) 4)]
                             (dom/set-css-property! dropdown-node "top" "unset")
                             (dom/set-css-property! dropdown-node "bottom" (str bottom "px")))
                           (let [top (+ (:bottom select-rect) 4)]
                             (dom/set-css-property! dropdown-node "bottom" "unset")
                             (dom/set-css-property! dropdown-node "top" (str top "px"))))
                         (dom/set-css-property! dropdown-node "left" (str (:left select-rect) "px"))
                         (dom/set-css-property! dropdown-node "width" (str (:width select-rect) "px"))
                         (dom/set-css-property! dropdown-node "position" "fixed")))))))]

          (.addEventListener js/document "mousedown" handler)

          (let [ro (js/ResizeObserver. (fn [_] (calculate)))]
            (when-let [node (mf/ref-val select-ref)]
              (.observe ro node))

            (.addEventListener js/window "resize" calculate)
            (.addEventListener js/window "scroll" calculate true)

            (calculate)

            (fn []
              (.removeEventListener js/document "mousedown" handler)
              (.disconnect ro)
              (.removeEventListener js/window "resize" calculate)
              (.removeEventListener js/window "scroll" calculate true))))))

    [:div {:class [wrapper-class (stl/css :select-wrapper)]
           :on-click on-click
           :ref select-ref
           :on-blur (when-not has-portal on-blur)}

     [:> :button props
      [:span {:class (stl/css-case :select-header true
                                   :header-icon has-icon?
                                   :header-icon-only (= variant "icon-only"))}
       (when ^boolean has-icon?
         (if (= variant "icon-only")
           [:> tooltip* {:content label
                         :trigger-ref icon-ref
                         :id (dm/str icon-id "-name")
                         :class (stl/css :option-text)}
            [:> icon* {:icon-id icon
                       :ref icon-ref
                       :aria-labelledby (dm/str icon-id "-name")}]]
           [:> icon* {:icon-id icon
                      :size "s"
                      :aria-hidden true}]))

       (when-not ^boolean (= variant "icon-only")
         [:span {:class (stl/css-case :header-label true
                                      :header-label-dimmed (or empty-selected-id? dimmed?))}
          (if ^boolean empty-selected-id? "--" label)])]

      [:> icon* {:icon-id i/arrow-down
                 :class (stl/css :arrow)
                 :size "s"
                 :aria-hidden true}]]

     (when ^boolean is-open
       (if has-portal
         (mf/portal
          (mf/html
           [:> options-dropdown* {:on-click on-option-click
                                  :id listbox-id
                                  :options options
                                  :selected selected-id
                                  :focused focused-id
                                  :align dropdown-alignment
                                  :empty-to-end empty-to-end
                                  :ref set-option-ref
                                  :wrapper-ref dropdown-wrapper-ref}])
          container)
         [:> options-dropdown* {:on-click on-option-click
                                :id listbox-id
                                :options options
                                :selected selected-id
                                :focused focused-id
                                :align dropdown-alignment
                                :empty-to-end empty-to-end
                                :ref set-option-ref}]))]))
