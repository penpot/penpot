;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC
(ns app.main.ui.ds.controls.shared.dropdown-navigation
  (:require
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(defn use-dropdown-navigation
  "Hook for keyboard navigation in dropdowns.

   Options:
   - focusable-ids: vector of focusable ids (already filtered)
   - nodes-ref: ref to a JS object mapping id -> DOM node
   - on-enter: fn called with focused-id when Enter is pressed
   - searchable: when true, nil focused-id means search input is focused
   - search-input-ref: ref to the search input DOM node
   - on-close: optional fn called when Esc/Tab is pressed"

  [{:keys [focusable-ids nodes-ref on-enter searchable search-input-ref on-close]}]
  (let [focused-id* (mf/use-state nil)
        focused-id  (deref focused-id*)

        focus-input!
        (mf/use-fn
         (mf/deps search-input-ref)
         (fn []
           (reset! focused-id* nil)
           (when-let [input (mf/ref-val search-input-ref)]
             (dom/focus! input))))

        on-key-down
        (mf/use-fn
         (mf/deps focused-id focusable-ids searchable)
         (fn [event]
           (cond
             (kbd/down-arrow? event)
             (do
               (dom/prevent-default event)
               (dom/stop-propagation event)
               (if (nil? focused-id)
                 (reset! focused-id* (first focusable-ids))
                 (let [idx           (or (first (keep-indexed #(when (= %2 focused-id) %1) focusable-ids)) -1)
                       next-idx      (mod (inc idx) (count focusable-ids))
                       wrap-to-input? (and ^boolean searchable
                                           (= next-idx 0)
                                           (= idx (dec (count focusable-ids))))]
                   (if wrap-to-input?
                     (focus-input!)
                     (reset! focused-id* (nth focusable-ids next-idx nil))))))

             (kbd/up-arrow? event)
             (do
               (dom/prevent-default event)
               (dom/stop-propagation event)
               (if (nil? focused-id)
                 (reset! focused-id* (last focusable-ids))
                 (let [idx           (or (first (keep-indexed #(when (= %2 focused-id) %1) focusable-ids)) 0)
                       prev-idx      (dec idx)
                       wrap-to-input? (and ^boolean searchable (= prev-idx -1))]
                   (if wrap-to-input?
                     (focus-input!)
                     (reset! focused-id* (nth focusable-ids (mod prev-idx (count focusable-ids)) nil))))))

             (kbd/enter? event)
             (when focused-id
               (dom/prevent-default event)
               (dom/stop-propagation event)
               (on-enter focused-id))

             (or (kbd/esc? event) (kbd/tab? event))
             (do
               (dom/prevent-default event)
               (dom/stop-propagation event)
               (reset! focused-id* nil)
               (when on-close (on-close))))))]

    (mf/with-effect [focused-id]
      (when (some? focused-id)
        (when-let [node (obj/get (mf/ref-val nodes-ref) focused-id)]
          (dom/scroll-into-view-if-needed! node {:block "nearest" :inline "nearest"}))))

    {:focused-id   focused-id
     :focused-id*  focused-id*
     :on-key-down  on-key-down}))