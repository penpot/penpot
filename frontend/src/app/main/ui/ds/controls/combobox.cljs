;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.controls.combobox
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.constants :refer [max-input-length]]
   [app.main.ui.ds.controls.select :refer [get-option handle-focus-change]]
   [app.main.ui.ds.controls.shared.options-dropdown :refer [options-dropdown* schema:option]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]
   [rumext.v2.util :as mfu]))

(def ^:private schema:combobox
  [:map
   [:id {:optional true} :string]
   [:options [:vector schema:option]]
   [:class {:optional true} :string]
   [:max-length {:optional true} :int]
   [:placeholder {:optional true} :string]
   [:disabled {:optional true} :boolean]
   [:default-selected {:optional true} :string]
   [:on-change {:optional true} fn?]
   [:empty-to-end {:optional true} [:maybe :boolean]]
   [:has-error {:optional true} :boolean]])

(mf/defc combobox*
  {::mf/schema schema:combobox}
  [{:keys [id options class placeholder disabled has-error default-selected max-length empty-to-end on-change] :rest props}]
  (let [;; NOTE: we use mfu/bean here for transparently handle
        ;; options provide as clojure data structures or javascript
        ;; plain objects and lists.
        options      (if (array? options)
                       (mfu/bean options)
                       options)
        empty-to-end (d/nilv empty-to-end false)

        is-open*     (mf/use-state false)
        is-open      (deref is-open*)

        selected-id* (mf/use-state default-selected)
        selected-id  (deref selected-id*)

        filter-id*   (mf/use-state "")
        filter-id    (deref filter-id*)

        focused-id*  (mf/use-state nil)
        focused-id   (deref focused-id*)

        combobox-ref (mf/use-ref nil)
        input-ref    (mf/use-ref nil)
        nodes-ref    (mf/use-ref nil)
        options-ref  (mf/use-ref nil)
        listbox-id   (mf/use-id)
        value-ref    (mf/use-ref nil)

        dropdown-options
        (mf/with-memo [options filter-id]
          (->> options
               (filterv (fn [option]
                          (let [option (str/lower (get option :id))
                                filter (str/lower filter-id)]
                            (str/includes? option filter))))
               (not-empty)))

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
             (reset! is-open* false)
             (reset! focused-id* nil)
             (when (fn? on-change)
               (on-change id)))))

        on-click
        (mf/use-fn
         (mf/deps disabled)
         (fn [event]
           (dom/stop-propagation event)
           (when-not disabled
             (when-not (deref is-open*)
               (reset! filter-id* ""))
             (swap! is-open* not))))


        on-blur
        (mf/use-fn
         (mf/deps on-change)
         (fn [event]
           (dom/stop-propagation event)
           (let [target   (dom/get-related-target event)
                 self-node (mf/ref-val combobox-ref)]
             (when-not (dom/is-child? self-node target)
               (reset! is-open* false)
               (reset! focused-id* nil)
               (when (fn? on-change)
                 (when-let [input-node (mf/ref-val input-ref)]
                   (on-change (dom/get-input-value input-node))))))))

        on-input-click
        (mf/use-fn
         (mf/deps disabled)
         (fn [event]
           (dom/stop-propagation event)
           (when-not disabled
             (when-not (deref is-open*)
               (reset! filter-id* ""))
             (reset! is-open* true))))

        on-input-focus
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (when-not disabled
             (dom/select-text! (.-target event)))))

        on-input-key-down
        (mf/use-fn
         (mf/deps is-open focused-id disabled)
         (fn [event]
           (dom/stop-propagation event)
           (when-not disabled
             (let [options (mf/ref-val options-ref)
                   len     (count options)
                   index   (d/index-of-pred options #(= focused-id (get % :id)))
                   index   (d/nilv index -1)
                   nodes   (mf/ref-val nodes-ref)]

               (if is-open
                 (cond
                   (kbd/home? event)
                   (handle-focus-change options focused-id* 0 nodes)

                   (kbd/up-arrow? event)
                   (let [new-index (if (= index -1)
                                     (dec len)
                                     (mod (- index 1) len))]
                     (handle-focus-change options focused-id* new-index nodes))


                   (kbd/down-arrow? event)
                   (let [new-index (if (= index -1)
                                     0
                                     (mod (+ index 1) len))]
                     (handle-focus-change options focused-id* new-index nodes))

                   (kbd/enter? event)
                   (do
                     (reset! selected-id* focused-id)
                     (reset! is-open* false)
                     (reset! focused-id* nil)
                     (dom/blur! (mf/ref-val input-ref))
                     (when (and (fn? on-change)
                                (some? focused-id))
                       (on-change focused-id)))

                   (kbd/esc? event)
                   (do (reset! is-open* false)
                       (reset! focused-id* nil)
                       (dom/blur! (mf/ref-val input-ref))))

                 (cond
                   (kbd/down-arrow? event)
                   (reset! is-open* true)

                   (or (kbd/esc? event) (kbd/enter? event))
                   (dom/blur! (mf/ref-val input-ref))))))))

        on-input-change
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (let [value (-> event
                           dom/get-target
                           dom/get-value)]
             (mf/set-ref-val! value-ref value)
             (reset! selected-id* value)
             (reset! filter-id* value)
             (reset! focused-id* nil))))

        selected-option
        (mf/with-memo [options selected-id]
          (when (d/not-empty? options)
            (get-option options selected-id)))

        icon
        (when selected-option
          (get selected-option :icon))]

    (mf/with-effect [dropdown-options]
      (mf/set-ref-val! options-ref dropdown-options))

    (mf/use-effect
     (mf/deps default-selected)
     (fn []
       (reset! selected-id* default-selected)))

    ;; On componnet unmount, save the new value if needed
    (mf/with-effect [on-change]
      (fn []
        (when-let [value (mf/ref-val value-ref)]
          (mf/set-ref-val! value-ref nil)
          (on-change value))))

    [:div {:ref combobox-ref
           :class (stl/css-case
                   :wrapper true
                   :has-error has-error
                   :disabled disabled)}

     [:div {:class [class (stl/css :combobox)]
            :on-blur on-blur
            :on-click on-click}

      [:span {:class (stl/css-case :header true
                                   :header-icon (some? icon))}
       (when icon
         [:> icon* {:icon-id icon
                    :size "s"
                    :aria-hidden true}])
       [:input {:id id
                :ref input-ref
                :type "text"
                :role "combobox"
                :class (stl/css :input)
                :auto-complete "off"
                :aria-autocomplete "both"
                :aria-expanded is-open
                :aria-controls listbox-id
                :aria-activedescendant focused-id
                :data-testid "combobox-input"
                :max-length (d/nilv max-length max-input-length)
                :disabled disabled
                :value (d/nilv selected-id "")
                :placeholder placeholder
                :on-change on-input-change
                :on-click on-input-click
                :on-focus on-input-focus
                :on-key-down on-input-key-down}]]

      (when (d/not-empty? options)
        [:button {:type "button"
                  :tab-index "-1"
                  :aria-expanded is-open
                  :aria-controls listbox-id
                  :class (stl/css :button-toggle-list)
                  :on-click on-click}
         [:> icon* {:icon-id i/arrow-down
                    :class (stl/css :arrow)
                    :size "s"
                    :aria-hidden true
                    :data-testid "combobox-open-button"}]])]

     (when (and ^boolean is-open
                ^boolean dropdown-options)
       [:> options-dropdown* {:on-click on-option-click
                              :options dropdown-options
                              :selected selected-id
                              :focused focused-id
                              :ref set-option-ref
                              :id listbox-id
                              :empty-to-end empty-to-end
                              :data-testid "combobox-options"}])]))
