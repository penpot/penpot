;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.controls.combobox
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.constants :refer [max-input-length]]
   [app.main.ui.ds.controls.shared.options-dropdown :refer [options-dropdown*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon* icon-list] :as i]
   [app.util.array :as array]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(def listbox-id-index (atom 0))

(defn- get-option
  [options id]
  (array/find #(= id (obj/get % "id")) options))

(defn- handle-focus-change
  [options focused* new-index options-nodes-refs]
  (let [option (aget options new-index)
        id     (obj/get option "id")
        nodes  (mf/ref-val options-nodes-refs)
        node   (obj/get nodes id)]
    (reset! focused* id)
    (dom/scroll-into-view-if-needed! node)))

(def ^:private schema:combobox-option
  [:and
   [:map {:title "option"}
    [:id :string]
    [:icon {:optional true}
     [:and :string [:fn #(contains? icon-list %)]]]
    [:label {:optional true} :string]
    [:aria-label {:optional true} :string]]
   [:fn {:error/message "invalid data: missing required props"}
    (fn [option]
      (or (and (contains? option :icon)
               (or (contains? option :label)
                   (contains? option :aria-label)))
          (contains? option :label)))]])

(def ^:private schema:combobox
  [:map
   [:id {:optional true} :string]
   [:options [:vector schema:combobox-option]]
   [:class {:optional true} :string]
   [:max-length {:optional true} :int]
   [:placeholder {:optional true} :string]
   [:disabled {:optional true} :boolean]
   [:default-selected {:optional true} :string]
   [:on-change {:optional true} fn?]
   [:empty-to-end {:optional true} :boolean]
   [:has-error {:optional true} :boolean]])

(mf/defc combobox*
  {::mf/schema schema:combobox}
  [{:keys [id options class placeholder disabled has-error default-selected max-length empty-to-end on-change] :rest props}]
  (let [is-open*        (mf/use-state false)
        is-open         (deref is-open*)

        selected-value* (mf/use-state default-selected)
        selected-value  (deref selected-value*)

        filter-value*   (mf/use-state "")
        filter-value    (deref filter-value*)

        focused-value*  (mf/use-state nil)
        focused-value   (deref focused-value*)

        combobox-ref        (mf/use-ref nil)
        input-ref           (mf/use-ref nil)
        options-nodes-refs  (mf/use-ref nil)
        options-ref         (mf/use-ref nil)
        listbox-id-ref      (mf/use-ref (dm/str "listbox-" (swap! listbox-id-index inc)))
        listbox-id          (mf/ref-val listbox-id-ref)

        dropdown-options
        (mf/use-memo
         (mf/deps options filter-value)
         (fn []
           (->> options
                (array/filter (fn [option]
                                (let [lower-option (.toLowerCase (obj/get option "id"))
                                      lower-filter (.toLowerCase filter-value)]
                                  (.includes lower-option lower-filter)))))))

        set-option-ref
        (mf/use-fn
         (fn [node id]
           (let [refs (or (mf/ref-val options-nodes-refs) #js {})
                 refs (if node
                        (obj/set! refs id node)
                        (obj/unset! refs id))]
             (mf/set-ref-val! options-nodes-refs refs))))

        on-option-click
        (mf/use-fn
         (mf/deps on-change)
         (fn [event]
           (dom/stop-propagation event)
           (let [node  (dom/get-current-target event)
                 id    (dom/get-data node "id")]
             (reset! selected-value* id)
             (reset! is-open* false)
             (reset! focused-value* nil)
             (when (fn? on-change)
               (on-change id)))))

        on-component-click
        (mf/use-fn
         (mf/deps disabled)
         (fn [event]
           (dom/stop-propagation event)
           (when-not disabled
             (when-not (deref is-open*)
               (reset! filter-value* ""))
             (swap! is-open* not))))

        on-component-blur
        (mf/use-fn
         (mf/deps on-change)
         (fn [event]
           (dom/stop-propagation event)
           (let [target (.-relatedTarget event)
                 outside? (not (.contains (mf/ref-val combobox-ref) target))]
             (when outside?
               (reset! is-open* false)
               (reset! focused-value* nil)
               (when (fn? on-change)
                 (on-change (dom/get-input-value (mf/ref-val input-ref))))))))

        on-input-click
        (mf/use-fn
         (mf/deps disabled)
         (fn [event]
           (dom/stop-propagation event)
           (when-not disabled
             (when-not (deref is-open*)
               (reset! filter-value* ""))
             (reset! is-open* true))))

        on-input-focus
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (when-not disabled
             (dom/select-text! (.-target event)))))

        on-input-key-down
        (mf/use-fn
         (mf/deps is-open focused-value disabled dropdown-options)
         (fn [event]
           (dom/stop-propagation event)
           (when-not disabled
             (let [len     (alength dropdown-options)
                   index   (array/find-index #(= (deref focused-value*) (obj/get % "id")) dropdown-options)]

               (when (< len 0)
                 (reset! index len))

               (if is-open
                 (cond
                   (kbd/home? event)
                   (handle-focus-change dropdown-options focused-value* 0 options-nodes-refs)

                   (kbd/up-arrow? event)
                   (let [new-index (if (= index -1)
                                     (dec len)
                                     (mod (- index 1) len))]
                     (handle-focus-change dropdown-options focused-value* new-index options-nodes-refs))


                   (kbd/down-arrow? event)
                   (let [new-index (if (= index -1)
                                     0
                                     (mod (+ index 1) len))]
                     (handle-focus-change dropdown-options focused-value* new-index options-nodes-refs))

                   (kbd/enter? event)
                   (do
                     (reset! selected-value* focused-value)
                     (reset! is-open* false)
                     (reset! focused-value* nil)
                     (dom/blur! (mf/ref-val input-ref))
                     (when (and (fn? on-change)
                                (some? focused-value))
                       (on-change focused-value)))

                   (kbd/esc? event)
                   (do (reset! is-open* false)
                       (reset! focused-value* nil)
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
             (reset! selected-value* value)
             (reset! filter-value* value)
             (reset! focused-value* nil))))

        selected-option (get-option options selected-value)
        icon (obj/get selected-option "icon")]

    (mf/with-effect [options]
      (mf/set-ref-val! options-ref options))

    (mf/use-effect
     (mf/deps default-selected)
     (fn []
       (reset! selected-value* default-selected)))

    [:div {:ref combobox-ref
           :class (stl/css-case
                   :wrapper true
                   :has-error has-error
                   :disabled disabled)}

     [:div {:class (dm/str class " " (stl/css :combobox))
            :on-blur on-component-blur
            :on-click on-component-click}

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
                :aria-activedescendant focused-value
                :data-testid "combobox-input"
                :max-length (d/nilv max-length max-input-length)
                :disabled disabled
                :value (d/nilv selected-value "")
                :placeholder placeholder
                :on-change on-input-change
                :on-click on-input-click
                :on-focus on-input-focus
                :on-key-down on-input-key-down}]]

      (when (d/not-empty? options)
        [:> :button {:type "button"
                     :tab-index "-1"
                     :aria-expanded is-open
                     :aria-controls listbox-id
                     :class (stl/css :button-toggle-list)
                     :on-click on-component-click}
         [:> icon* {:icon-id i/arrow
                    :class (stl/css :arrow)
                    :size "s"
                    :aria-hidden true
                    :data-testid "combobox-open-button"}]])]

     (when (and is-open (seq dropdown-options))
       [:> options-dropdown* {:on-click on-option-click
                              :options dropdown-options
                              :selected selected-value
                              :focused focused-value
                              :set-ref set-option-ref
                              :id listbox-id
                              :empty-to-end empty-to-end
                              :data-testid "combobox-options"}])]))
