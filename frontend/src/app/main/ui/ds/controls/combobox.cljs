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
   [app.main.ui.ds.foundations.assets.icon :refer [icon* icon-list] :as i]
   [app.util.array :as array]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [rumext.v2 :as mf]
   [app.main.ui.ds.controls.shared.options-dropdown :refer [options-dropdown*]]))

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
   [:options [:vector {:min 1} schema:combobox-option]]
   [:class {:optional true} :string]
   [:disabled {:optional true} :boolean]
   [:default-selected {:optional true} :string]
   [:on-change {:optional true} fn?]])

(mf/defc combobox*
  {::mf/props :obj
   ::mf/schema schema:combobox}
  [{:keys [options class disabled default-selected on-change] :rest props}]
  (let [open* (mf/use-state false)
        open  (deref open*)

        selected* (mf/use-state  default-selected)
        selected  (deref selected*)

        focused* (mf/use-state nil)
        focused  (deref focused*)

        filter* (mf/use-state "")
        filter  (deref filter*)

        has-focus* (mf/use-state false)
        has-focus (deref has-focus*)

        dropdown-options
         (mf/use-memo
         (mf/deps options filter)
         (fn []
           (->> options
                (array/filter (fn [option]
                                (let [lower-option (.toLowerCase (obj/get option "id"))
                                      lower-filter (.toLowerCase filter)]
                                  (.includes lower-option lower-filter)))))))

        on-click
        (mf/use-fn
         (mf/deps disabled)
         (fn [event]
           (dom/stop-propagation event)
           (when-not disabled
            (reset! has-focus* true)
            (if (= "INPUT" (.-tagName (.-target event)))
              (reset! open* true)
              (swap! open* not)
            ))))

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
        listbox-id         (str "listbox-" (swap! listbox-id-index inc))
        combobox-ref (mf/use-ref nil)

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
          (when-not open
            (reset! open* true))

           (when-not disabled
             (let [options dropdown-options
                    focused (deref focused*)
                    len     (alength options)
                    index   (array/find-index #(= (deref focused*) (obj/get % "id")) options)]
               (dom/stop-propagation event)

               (cond
                 (kbd/home? event)
                 (handle-focus-change options focused* 0 options-nodes-refs)

                 (kbd/up-arrow? event)
                 (handle-focus-change options focused* (mod (- index 1) len) options-nodes-refs)

                 (kbd/down-arrow? event)
                 (handle-focus-change options focused* (mod (+ index 1) len) options-nodes-refs)

                 (or (kbd/space? event) (kbd/enter? event))
                 (when (deref open*)
                   (dom/prevent-default event)
                   (handle-selection focused* selected* open*))

                 (kbd/esc? event)
                 (do (reset! open* false)
                     (reset! focused* nil)))))))

          on-input-change
          (mf/use-fn
           (fn [event]
           (let [value (.-value (.-currentTarget event))]
              (reset! filter* value)
              (reset! selected* value)
              (reset! focused* nil)
            )))

        class (dm/str class " " (stl/css :combobox))

        props (mf/spread-props props {:class class
                                      :role "combobox"
                                      :aria-autocomplete "list"
                                      :aria-controls listbox-id
                                      :aria-haspopup "listbox"
                                      :aria-activedescendant focused
                                      :aria-expanded open
                                      :disabled disabled
                                      :on-click on-click
                                      :on-focus (fn [_] (reset! has-focus* true))
                                      :on-blur on-blur})

        selected-option (get-option options selected)
        label (obj/get selected-option "label")
        icon (obj/get selected-option "icon")
    ]

    (mf/with-effect [options]
      (mf/set-ref-val! options-ref options))

    [:div {:ref combobox-ref :class (stl/css-case
                    :combobox-wrapper true
                    :focused has-focus)}

     [:> :div props
      [:span {:class (stl/css-case :combobox-header true
                                   :header-icon (some? icon))}
       (when icon
         [:> icon* {:id icon
                    :size "s"
                    :aria-hidden true}])
       [:input {
          :type "text"
          :class (stl/css :input)
          :disabled disabled
          :value selected
          :on-change on-input-change
          :on-key-down on-key-down
        }]]

      [:> :button {
        :tab-index "-1"
        :class (stl/css :button-toggle-list)
        :on-click on-click}
        [:> icon* {:id i/arrow
          :class (stl/css :arrow)
          :size "s"
          :aria-hidden true}]]]

    (when (and open (seq dropdown-options))
       [:> options-dropdown* {:on-click on-option-click
                  :options dropdown-options
                  :selected selected
                  :focused focused
                  :set-ref set-ref
                  :id listbox-id}])]))
