;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.common
  (:require
   [app.common.logging :as log]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.undo :as dwu]
   [app.main.worker :as uw]
   [beicon.core :as rx]
   [potok.core :as ptk]))

;; Change this to :info :debug or :trace to debug this module
(log/set-level! :warn)

(defn initialized?
  "Check if the state is properly intialized in a workspace. This means
  it has the `:current-page-id` and `:current-file-id` properly set."
  [state]
  (and (uuid? (:current-file-id state))
       (uuid? (:current-page-id state))))

;; --- Helpers

(defn interrupt? [e] (= e :interrupt))

;; --- Selection Index Handling

(defn initialize-indices
  [{:keys [file-raw] :as bundle}]
  (ptk/reify ::setup-selection-index
    ptk/WatchEvent
    (watch [_ _ _]
      (let [msg {:cmd :initialize-indices
                 :file-raw file-raw}]
        (->> (uw/ask! msg)
             (rx/map (constantly ::index-initialized)))))))

;; These functions should've been in `src/app/main/data/workspace/undo.cljs` but doing that causes
;; a circular dependency with `src/app/main/data/workspace/changes.cljs`
(def undo
  (ptk/reify ::undo
    ptk/WatchEvent
    (watch [it state _]
      (let [edition (get-in state [:workspace-local :edition])
            drawing (get state :workspace-drawing)]
        ;; Editors handle their own undo's
        (when (and (nil? edition) (nil? (:object drawing)))
          (let [undo  (:workspace-undo state)
                items (:items undo)
                index (or (:index undo) (dec (count items)))]
            (when-not (or (empty? items) (= index -1))
              (let [changes (get-in items [index :undo-changes])]
                (rx/of (dwu/materialize-undo changes (dec index))
                       (dch/commit-changes {:redo-changes changes
                                            :undo-changes []
                                            :save-undo? false
                                            :origin it}))))))))))

(def redo
  (ptk/reify ::redo
    ptk/WatchEvent
    (watch [it state _]
      (let [edition (get-in state [:workspace-local :edition])
            drawing (get state :workspace-drawing)]
        (when-not (or (some? edition) (not-empty drawing))
          (let [undo  (:workspace-undo state)
                items (:items undo)
                index (or (:index undo) (dec (count items)))]
            (when-not (or (empty? items) (= index (dec (count items))))
              (let [changes (get-in items [(inc index) :redo-changes])]
                (rx/of (dwu/materialize-undo changes (inc index))
                       (dch/commit-changes {:redo-changes changes
                                            :undo-changes []
                                            :origin it
                                            :save-undo? false}))))))))))

(defn undo-to-index
  "Repeat undoing or redoing until dest-index is reached."
  [dest-index]
  (ptk/reify ::undo-to-index
    ptk/WatchEvent
    (watch [it state _]
      (let [edition (get-in state [:workspace-local :edition])
            drawing (get state :workspace-drawing)]
        (when-not (or (some? edition) (not-empty drawing))
          (let [undo  (:workspace-undo state)
                items (:items undo)
                index (or (:index undo) (dec (count items)))]
            (when (and (some? items)
                       (<= 0 dest-index (dec (count items))))
              (let [changes (vec (apply concat
                                        (cond
                                          (< dest-index index)
                                          (->> (subvec items (inc dest-index) (inc index))
                                               (reverse)
                                               (map :undo-changes))
                                          (> dest-index index)
                                          (->> (subvec items (inc index) (inc dest-index))
                                               (map :redo-changes))
                                          :else [])))]
                (when (seq changes)
                  (rx/of (dwu/materialize-undo changes dest-index)
                         (dch/commit-changes {:redo-changes changes
                                              :undo-changes []
                                              :origin it
                                              :save-undo? false})))))))))))

