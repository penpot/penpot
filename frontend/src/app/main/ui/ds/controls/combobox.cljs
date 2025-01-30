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

(defn- handle-selection
  [focused* selected* open*]
  (when-let [focused (deref focused*)]
    (reset! selected* focused))
  (reset! open* false)
  (reset! focused* nil))

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
   [:disabled {:optional true} :boolean]
   [:default-selected {:optional true} :string]
   [:on-change {:optional true} fn?]
   [:has-error {:optional true} :boolean]])

(mf/defc combobox*
  {::mf/props :obj
   ::mf/schema schema:combobox}
  [{:keys [id options class disabled has-error default-selected on-change] :rest props}]
  (let [open* (mf/use-state false)
        open  (deref open*)

        selected* (mf/use-state  default-selected)
        selected  (deref selected*)

        filter-value* (mf/use-state "")
        filter-value  (deref filter-value*)

        focused* (mf/use-state nil)
        focused  (deref focused*)

        has-focus* (mf/use-state false)
        has-focus  (deref has-focus*)

        dropdown-options
        (mf/use-memo
         (mf/deps options filter-value)
         (fn []
           (->> options
                (array/filter (fn [option]
                                (let [lower-option (.toLowerCase (obj/get option "id"))
                                      lower-filter (.toLowerCase filter-value)]
                                  (.includes lower-option lower-filter)))))))

        on-click
        (mf/use-fn
         (mf/deps disabled)
         (fn [event]
           (dom/stop-propagation event)
           (when-not disabled
             (reset! has-focus* true)
             (when-not (deref open*) (reset! filter-value* ""))
             (if (= "INPUT" (.-tagName (.-target event)))
               (reset! open* true)
               (swap! open* not)))))

        on-option-click
        (mf/use-fn
         (mf/deps on-change)
         (fn [event]
           (let [node  (dom/get-current-target event)
                 id    (dom/get-data node "id")]
             (reset! selected* id)
             (reset! focused* nil)
             (reset! open* false)
             (when (fn? on-change)
               (on-change id)))))

        options-nodes-refs  (mf/use-ref nil)
        options-ref         (mf/use-ref nil)
        listbox-id-ref      (mf/use-ref (dm/str "listbox-" (swap! listbox-id-index inc)))
        listbox-id          (mf/ref-val listbox-id-ref)
        combobox-ref        (mf/use-ref nil)

        set-ref
        (mf/use-fn
         (fn [node id]
           (let [refs (or (mf/ref-val options-nodes-refs) #js {})
                 refs (if node
                        (obj/set! refs id node)
                        (obj/unset! refs id))]
             (mf/set-ref-val! options-nodes-refs refs))))

        on-blur
        (mf/use-fn
         (fn [event]
           (let [target (.-relatedTarget event)
                 outside? (not (.contains (mf/ref-val combobox-ref) target))]
             (when outside?
               (reset! focused* nil)
               (reset! open* false)
               (reset! has-focus* false)))))

        on-key-down
        (mf/use-fn
         (mf/deps open focused disabled dropdown-options)
         (fn [event]
           (when-not disabled
             (let [options dropdown-options
                   focused (deref focused*)
                   len     (alength options)
                   index   (array/find-index #(= (deref focused*) (obj/get % "id")) options)]
               (dom/stop-propagation event)

               (when (< len 0)
                 (reset! index len))

               (cond
                 (and (not open) (kbd/down-arrow? event))
                 (reset! open* true)

                 open
                 (cond
                   (kbd/home? event)
                   (handle-focus-change options focused* 0 options-nodes-refs)

                   (kbd/up-arrow? event)
                   (let [new-index (if (= index -1)
                                     (dec len)
                                     (mod (- index 1) len))]
                     (handle-focus-change options focused* new-index options-nodes-refs))


                   (kbd/down-arrow? event)
                   (let [new-index (if (= index -1)
                                     0
                                     (mod (+ index 1) len))]
                     (handle-focus-change options focused* new-index options-nodes-refs))

                   (kbd/enter? event)
                   (when (deref open*)
                     (dom/prevent-default event)
                     (handle-selection focused* selected* open*)
                     (when (fn? on-change)
                       (on-change focused)))

                   (kbd/esc? event)
                   (do (reset! open* false)
                       (reset! focused* nil))))))))

        on-input-change
        (mf/use-fn
         (fn [event]
           (let [value (-> event dom/get-target dom/get-value)]
             (reset! selected* value)
             (reset! filter-value* value)
             (reset! focused* nil)
             (when (fn? on-change)
               (on-change value)))))
        on-focus
        (mf/use-fn
         (fn [_] (reset! has-focus* true)))

        class (dm/str class " " (stl/css :combobox))

        selected-option (get-option options selected)
        icon (obj/get selected-option "icon")]

    (mf/with-effect [options]
      (mf/set-ref-val! options-ref options))

    [:div {:ref combobox-ref
           :class (stl/css-case
                   :combobox-wrapper true
                   :focused has-focus
                   :has-error has-error
                   :disabled disabled)}

     [:div {:class class
            :on-click on-click
            :on-focus on-focus
            :on-blur on-blur}
      [:span {:class (stl/css-case :combobox-header true
                                   :header-icon (some? icon))}
       (when icon
         [:> icon* {:icon-id icon
                    :size "s"
                    :aria-hidden true}])
       [:input {:id id
                :type "text"
                :role "combobox"
                :autoComplete "off"
                :aria-autocomplete "both"
                :aria-expanded open
                :aria-controls listbox-id
                :aria-activedescendant focused
                :class (stl/css :input)
                :data-testid "combobox-input"
                :disabled disabled
                :value selected
                :on-change on-input-change
                :on-key-down on-key-down}]]

      (when (d/not-empty? options)
        [:> :button {:type "button"
                     :tab-index "-1"
                     :aria-expanded open
                     :aria-controls listbox-id
                     :class (stl/css :button-toggle-list)
                     :on-click on-click}
         [:> icon* {:icon-id i/arrow
                    :class (stl/css :arrow)
                    :size "s"
                    :aria-hidden true
                    :data-testid "combobox-open-button"}]])]

     (when (and open (seq dropdown-options))
       [:> options-dropdown* {:on-click on-option-click
                              :options dropdown-options
                              :selected selected
                              :focused focused
                              :set-ref set-ref
                              :id listbox-id
                              :data-testid "combobox-options"}])]))
