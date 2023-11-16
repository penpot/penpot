;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.common
  (:require
   [app.common.data.macros :as dm]
   [app.common.logging :as log]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.undo :as dwu]
   [app.util.router :as rt]
   [beicon.core :as rx]
   [potok.core :as ptk]))

;; Change this to :info :debug or :trace to debug this module
(log/set-level! :warn)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn initialized?
  "Check if the state is properly initialized in a workspace. This means
  it has the `:current-page-id` and `:current-file-id` properly set."
  [state]
  (and (uuid? (:current-file-id state))
       (uuid? (:current-page-id state))))

(defn interrupt?
  [e]
  (= e :interrupt))

(defn- assure-valid-current-page
  []
  (ptk/reify ::assure-valid-current-page
    ptk/WatchEvent
    (watch [_ state _]
      (let [current_page (:current-page-id state)
            pages        (get-in state [:workspace-data :pages])
            exists? (some #(= current_page %) pages)

            project-id (:current-project-id state)
            file-id    (:current-file-id state)
            pparams    {:file-id file-id :project-id project-id}
            qparams    {:page-id (first pages)}]
        (if exists?
          (rx/empty)
          (rx/of (rt/nav :workspace pparams qparams)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; UNDO
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare undo-to-index)

;; These functions should've been in
;; `src/app/main/data/workspace/undo.cljs` but doing that causes a
;; circular dependency with `src/app/main/data/workspace/changes.cljs`

(def undo
  (ptk/reify ::undo
    ptk/WatchEvent
    (watch [it state _]
      (let [objects (wsh/lookup-page-objects state)
            edition (get-in state [:workspace-local :edition])
            drawing (get state :workspace-drawing)]

        ;; Editors handle their own undo's
        (when (or (and (nil? edition) (nil? (:object drawing)))
                  (ctl/grid-layout? objects edition))
          (let [undo  (:workspace-undo state)
                items (:items undo)
                index (or (:index undo) (dec (count items)))]
            (when-not (or (empty? items) (= index -1))
              (let [item (get items index)
                    changes (:undo-changes item)
                    undo-group (:undo-group item)

                    find-first-group-idx
                    (fn [index]
                      (if (= (dm/get-in items [index :undo-group]) undo-group)
                        (recur (dec index))
                        (inc index)))

                    undo-group-index
                    (when undo-group
                      (find-first-group-idx index))]

                (if undo-group
                  (rx/of (undo-to-index (dec undo-group-index)))
                  (rx/of (dwu/materialize-undo changes (dec index))
                         (dch/commit-changes {:redo-changes changes
                                              :undo-changes []
                                              :save-undo? false
                                              :origin it})
                         (assure-valid-current-page)))))))))))

(def redo
  (ptk/reify ::redo
    ptk/WatchEvent
    (watch [it state _]
      (let [edition (get-in state [:workspace-local :edition])
            drawing (get state :workspace-drawing)]
        (when (and (nil? edition) (or (empty? drawing) (= :curve (:tool drawing))))
          (let [undo  (:workspace-undo state)
                items (:items undo)
                index (or (:index undo) (dec (count items)))]
            (when-not (or (empty? items) (= index (dec (count items))))
              (let [item (get items (inc index))
                    changes (:redo-changes item)
                    undo-group (:undo-group item)
                    find-last-group-idx (fn flgidx [index]
                                          (let [item (get items index)]
                                            (if (= (:undo-group item) undo-group)
                                              (flgidx (inc index))
                                              (dec index))))

                    redo-group-index (when undo-group
                                       (find-last-group-idx (inc index)))]
                (if undo-group
                  (rx/of (undo-to-index redo-group-index))
                  (rx/of (dwu/materialize-undo changes (inc index))
                         (dch/commit-changes {:redo-changes changes
                                              :undo-changes []
                                              :origin it
                                              :save-undo? false})))))))))))

(defn undo-to-index
  "Repeat undoing or redoing until dest-index is reached."
  [dest-index]
  (ptk/reify ::undo-to-index
    ptk/WatchEvent
    (watch [it state _]
      (let [objects (wsh/lookup-page-objects state)
            edition (get-in state [:workspace-local :edition])
            drawing (get state :workspace-drawing)]
        (when-not (and (or (some? edition) (some? (:object drawing)))
                       (not (ctl/grid-layout? objects edition)))
          (let [undo  (:workspace-undo state)
                items (:items undo)
                index (or (:index undo) (dec (count items)))]
            (when (and (some? items)
                       (<= -1 dest-index (dec (count items))))
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Toolbar
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn toggle-toolbar-visibility
   []
  (ptk/reify ::toggle-toolbar-visibility
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :hide-toolbar] not))))

(defn hide-toolbar
  []
  (ptk/reify ::hide-toolbar
    ptk/UpdateEvent
    (update [_ state]
            (assoc-in state [:workspace-local :hide-toolbar] true))))

(defn show-toolbar
  []
  (ptk/reify ::show-toolbar
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :hide-toolbar] false))))
