;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.forms.controls.combobox-navigation
  (:require
   [app.main.ui.workspace.tokens.management.forms.controls.utils :refer [focusable-options]]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

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
  (let [ids (vec (map :id focusables))
        idx (.indexOf (clj->js ids) focused-id)
        count (count ids)]
    (case direction
      :down (nth ids (mod (inc idx) count) nil)
      :up   (nth ids (mod (if (= idx -1) 0 (dec idx)) count) nil))))

(defn use-navigation
  [{:keys [is-open options nodes-ref is-open* toggle-dropdown on-enter get-selected-id]}]

  (let [focused-id* (mf/use-state nil)
        focused-id  (deref focused-id*)

        on-key-down
        (mf/use-fn
         (mf/deps is-open focused-id)
         (fn [event]
           (let [up?            (kbd/up-arrow? event)
                 down?          (kbd/down-arrow? event)
                 enter?         (kbd/enter? event)
                 esc?           (kbd/esc? event)
                 tab?           (kbd/tab? event)
                 open-dropdown  (kbd/is-key? event "{")
                 close-dropdown (kbd/is-key? event "}")
                 options        (if (delay? options) @options options)]

             (cond
               down?
               (do
                 (dom/prevent-default event)
                 (let [focusables (focusable-options options)]
                   (cond
                     ;; Dropdown open: move focus to next option
                     is-open
                     (when (seq focusables)
                       (let [next-id (next-focus-id focusables focused-id :down)]
                         (reset! focused-id* next-id)))

                     ;; Dropdown closed with options: open and focus first
                     (seq focusables)
                     (do
                       (toggle-dropdown event)
                       (when get-selected-id
                         (get-selected-id))
                       (reset! focused-id* (first-focusable-id focusables)))

                     :else nil)))

               up?
               (when is-open
                 (dom/prevent-default event)
                 (let [focusables (focusable-options options)
                       next-id (next-focus-id focusables focused-id :up)]
                   (reset! focused-id* next-id)))

               open-dropdown
               (do
                 (reset! is-open* true)
                 (reset! focused-id* nil))

               close-dropdown
               (reset! is-open* false)

               enter?
               (do
                 (when (and is-open focused-id)
                   (let [focusables (focusable-options options)]
                     (dom/prevent-default event)
                     (when (some #(= (:id %) focused-id) focusables)
                       (on-enter focused-id)))))

               esc?
               (when is-open
                 (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (reset! is-open* false))

               tab?
               (when is-open
                 (reset! is-open* false)
                 (reset! focused-id* nil))

               :else nil))))]

    (mf/with-effect [is-open]
      (when (not is-open)
        (reset! focused-id* nil)))

    ;; auto scroll when key down
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