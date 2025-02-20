;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.controls.select
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.style :as stl])
  (:require
   [app.main.ui.ds.controls.shared.options-dropdown :refer [options-dropdown*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon* icon-list] :as i]
   [app.util.array :as array]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(def listbox-id-index (atom 0))

(def ^:private schema:select-option
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

(defn- get-option
  [options id]
  (or (array/find #(= id (obj/get % "id")) options)
      (aget options 0)))

(defn- get-selected-option-id
  [options default]
  (let [option (get-option options default)]
    (obj/get option "id")))

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

(def ^:private schema:select
  [:map
   [:options [:vector {:min 1} schema:select-option]]
   [:class {:optional true} :string]
   [:disabled {:optional true} :boolean]
   [:default-selected {:optional true} :string]
   [:on-change {:optional true} fn?]])

(mf/defc select*
  {::mf/props :obj
   ::mf/schema schema:select}
  [{:keys [options class disabled default-selected on-change] :rest props}]
  (let [open* (mf/use-state false)
        open  (deref open*)

        listbox-id-ref     (mf/use-ref (dm/str "select-listbox-" (swap! listbox-id-index inc)))
        options-nodes-refs (mf/use-ref nil)
        options-ref        (mf/use-ref nil)
        select-ref         (mf/use-ref nil)
        listbox-id         (mf/ref-val listbox-id-ref)

        selected* (mf/use-state  #(get-selected-option-id options default-selected))
        selected  (deref selected*)

        focused* (mf/use-state nil)
        focused  (deref focused*)

        has-focus* (mf/use-state false)
        has-focus  (deref has-focus*)

        on-click
        (mf/use-fn
         (mf/deps disabled)
         (fn [event]
           (dom/stop-propagation event)
           (reset! has-focus* true)
           (when-not disabled
             (swap! open* not))))

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
                 outside? (not (.contains (mf/ref-val select-ref) target))]
             (when outside?
               (reset! focused* nil)
               (reset! open* false)
               (reset! has-focus* false)))))

        on-key-down
        (mf/use-fn
         (mf/deps focused disabled)
         (fn [event]
           (when-not disabled
             (let [options (mf/ref-val options-ref)
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

        on-focus
        (mf/use-fn
         (fn [_] (reset! has-focus* true)))

        class (dm/str class " " (stl/css-case :select true
                                              :focused has-focus))

        props (mf/spread-props props {:class class
                                      :role "combobox"
                                      :aria-controls listbox-id
                                      :aria-haspopup "listbox"
                                      :aria-activedescendant focused
                                      :aria-expanded open
                                      :on-key-down on-key-down
                                      :disabled disabled
                                      :on-click on-click})

        selected-option (get-option options selected)
        label (obj/get selected-option "label")
        icon (obj/get selected-option "icon")]

    (mf/with-effect [options]
      (mf/set-ref-val! options-ref options))

    [:div {:class (stl/css :select-wrapper)
           :on-click on-click
           :on-focus on-focus
           :ref select-ref
           :on-blur on-blur}
     [:> :button props
      [:span {:class (stl/css-case :select-header true
                                   :header-icon (some? icon))}
       (when icon
         [:> icon* {:icon-id icon
                    :size "s"
                    :aria-hidden true}])
       [:span {:class (stl/css :header-label)}
        label]]
      [:> icon* {:icon-id i/arrow
                 :class (stl/css :arrow)
                 :size "m"
                 :aria-hidden true}]]
     (when open
       [:> options-dropdown* {:on-click on-option-click
                              :id listbox-id
                              :options options
                              :selected selected
                              :focused focused
                              :set-ref set-ref}])]))
