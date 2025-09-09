;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.controls.select
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.ui.ds.controls.shared.options-dropdown :refer [options-dropdown* schema:option]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [clojure.string :as str]
   [rumext.v2 :as mf]
   [rumext.v2.util :as mfu]))

(defn get-option
  [options id]
  (let [options (if (delay? options) @options options)]
    (or (d/seek #(= id (get % :id)) options)
        (nth options 0))))

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
   [:disabled {:optional true} :boolean]
   [:default-selected {:optional true} :string]
   [:empty-to-end {:optional true} [:maybe :boolean]]
   [:on-change {:optional true} fn?]])

(mf/defc select*
  {::mf/schema schema:select}
  [{:keys [options class disabled default-selected empty-to-end on-change] :rest props}]
  (let [;; NOTE: we use mfu/bean here for transparently handle
        ;; options provide as clojure data structures or javascript
        ;; plain objects and lists.
        options      (if (array? options)
                       (mfu/bean options)
                       options)

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
        (mf/spread-props props {:class [class (stl/css :select)]
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
          (get-option options selected-id))

        label
        (get selected-option :label)

        icon
        (get selected-option :icon)

        has-icon?
        (some? icon)]

    (mf/with-effect [options]
      (mf/set-ref-val! options-ref options))

    [:div {:class (stl/css :select-wrapper)
           :on-click on-click
           :ref select-ref
           :on-blur on-blur}

     [:> :button props
      [:span {:class (stl/css-case :select-header true
                                   :header-icon has-icon?)}
       (when ^boolean has-icon?
         [:> icon* {:icon-id icon
                    :size "s"
                    :aria-hidden true}])
       [:span {:class (stl/css-case :header-label true
                                    :header-label-dimmed empty-selected-id?)}
        (if ^boolean empty-selected-id? "--" label)]]

      [:> icon* {:icon-id i/arrow-down
                 :class (stl/css :arrow)
                 :size "s"
                 :aria-hidden true}]]

     (when ^boolean is-open
       [:> options-dropdown* {:on-click on-option-click
                              :id listbox-id
                              :options options
                              :selected selected-id
                              :focused focused-id
                              :empty-to-end empty-to-end
                              :ref set-option-ref}])]))
