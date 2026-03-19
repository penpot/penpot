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
        idx (if (= idx -1) -1 idx)
        next-idx (case direction
                   :down (min (dec (count ids)) (inc idx))
                   :up   (max 0 (dec (if (= idx -1) 0 idx))))]
    (nth ids next-idx nil)))

(defn use-navigation
  [{:keys [is-open options nodes-ref is-open* toggle-dropdown on-enter]}]

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
                 open-dropdown  (kbd/is-key? event "{")
                 close-dropdown (kbd/is-key? event "}")
                 options        (if (delay? options) @options options)]

             (cond
               down?
               (do
                 (dom/prevent-default event)
                 (let [focusables (focusable-options options)]
                   (cond
                     is-open
                     (when (seq focusables)
                       (let [next-id (next-focus-id focusables focused-id :down)]
                         (reset! focused-id* next-id)))

                     (seq focusables)
                     (do
                       (toggle-dropdown event)
                       (reset! focused-id* (first-focusable-id focusables)))

                     :else
                     nil)))

               up?
               (when is-open
                 (dom/prevent-default event)
                 (let [focusables (focusable-options options)
                       next-id (next-focus-id focusables focused-id :up)]
                   (reset! focused-id* next-id)))

               open-dropdown
               (reset! is-open* true)

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
               (do
                 (dom/prevent-default event)
                 (reset! is-open* false))
               :else nil))))]

    ;; Initial focus on first option
    (mf/with-effect [is-open options]
      (when is-open
        (let [opts (if (delay? options) @options options)
              focusables (focusable-options opts)
              ids (set (map :id focusables))]
          (when (and (seq focusables)
                     (not (contains? ids focused-id)))
            (reset! focused-id* (:id (first focusables)))))))

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