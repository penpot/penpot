;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.path.undo
  (:require
   [app.common.data :as d]
   [app.common.data.undo-stack :as u]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.edition :as-alias dwe]
   [app.main.data.workspace.pages :as-alias dwpg]
   [app.main.data.workspace.path.changes :as changes]
   [app.main.data.workspace.path.common :as common]
   [app.main.data.workspace.path.state :as st]
   [app.main.store :as store]
   [beicon.v2.core :as rx]
   [okulary.core :as l]
   [potok.v2.core :as ptk]))

(defn undo-event?
  [event]
  (= :app.main.data.workspace.common/undo (ptk/type event)))

(defn redo-event?
  [event]
  (= :app.main.data.workspace.common/redo (ptk/type event)))

(defn- make-entry [state]
  (let [id (st/get-path-id state)
        shape (st/get-path state)]
    {:content (:content shape)
     :selrect (:selrect shape)
     :points  (:points shape)
     :preview (get-in state [:workspace-local :edit-path id :preview])
     :last-point (get-in state [:workspace-local :edit-path id :last-point])
     :prev-handler (get-in state [:workspace-local :edit-path id :prev-handler])}))

(defn- load-entry [state {:keys [content selrect points preview last-point prev-handler]}]
  (let [id (st/get-path-id state)
        old-content (st/get-path state :content)]
    (-> state
        (d/assoc-in-when (st/get-path-location state :content) content)
        (d/assoc-in-when (st/get-path-location state :selrect) selrect)
        (d/assoc-in-when (st/get-path-location state :points) points)
        (d/update-in-when
         [:workspace-local :edit-path id]
         assoc
         :preview preview
         :last-point last-point
         :prev-handler prev-handler
         :old-content old-content))))

(defn undo-path []
  (ptk/reify ::undo-path
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)
            undo-stack (-> (get-in state [:workspace-local :edit-path id :undo-stack])
                           (u/undo))
            entry (u/peek undo-stack)]
        (cond-> state
          (some? entry)
          (-> (load-entry entry)
              (d/assoc-in-when
               [:workspace-local :edit-path id :undo-stack]
               undo-stack)))))

    ptk/WatchEvent
    (watch [_ state _]
      (let [id (st/get-path-id state)
            undo-stack (get-in state [:workspace-local :edit-path id :undo-stack])]
        (if (> (:index undo-stack) 0)
          (rx/of (changes/save-path-content {:preserve-move-to true}))
          (rx/of (changes/save-path-content {:preserve-move-to true})
                 (common/finish-path)
                 (dwc/show-toolbar)))))))

(defn redo-path []
  (ptk/reify ::redo-path
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)
            undo-stack (-> (get-in state [:workspace-local :edit-path id :undo-stack])
                           (u/redo))
            entry (u/peek undo-stack)]
        (-> state
            (load-entry entry)
            (d/assoc-in-when
             [:workspace-local :edit-path id :undo-stack]
             undo-stack))))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (changes/save-path-content)))))

(defn merge-head
  "Joins the head with the previous undo in one. This is done so when the user changes a
  node handlers after adding it the undo merges both in one operation only"
  []
  (ptk/reify ::merge-head
    ptk/UpdateEvent
    (update [_ state]
      (let [id    (st/get-path-id state)
            stack (get-in state [:workspace-local :edit-path id :undo-stack])
            head  (u/peek stack)
            stack (-> stack (u/undo) (u/fixup head))]
        (-> state
            (d/assoc-in-when
             [:workspace-local :edit-path id :undo-stack]
             stack))))))

(defn add-undo-entry []
  (ptk/reify ::add-undo-entry
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)
            entry (make-entry state)]
        (-> state
            (d/update-in-when
             [:workspace-local :edit-path id :undo-stack]
             u/append entry))))))

(defn end-path-undo
  []
  (ptk/reify ::end-path-undo
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (d/update-in-when
           [:workspace-local :edit-path (st/get-path-id state)]
           dissoc :undo-lock :undo-stack)))))

(defn- stop-undo? [event]
  (let [type (ptk/type event)]
    (or (= ::dwe/clear-edition-mode type)
        (= ::dwpg/finalize-page type))))

(def path-content-ref
  (letfn [(selector [state]
            (st/get-path state :content))]
    (l/derived selector store/state)))

(defn start-path-undo
  []
  (let [lock (uuid/next)]
    (ptk/reify ::start-path-undo
      ptk/UpdateEvent
      (update [_ state]
        (let [undo-lock (get-in state [:workspace-local :edit-path (st/get-path-id state) :undo-lock])]
          (cond-> state
            (not undo-lock)
            (update-in [:workspace-local :edit-path (st/get-path-id state)]
                       assoc
                       :undo-lock lock
                       :undo-stack (u/make-stack)))))

      ptk/WatchEvent
      (watch [_ state stream]
        (let [undo-lock (get-in state [:workspace-local :edit-path (st/get-path-id state) :undo-lock])]
          (when (= undo-lock lock)
            (let [stop-undo-stream (->> stream
                                        (rx/filter stop-undo?)
                                        (rx/take 1))]
              (rx/concat
               (->> (rx/from-atom path-content-ref {:emit-current-value? true})
                    (rx/take-until stop-undo-stream)
                    (rx/filter (comp not nil?))
                    (rx/map #(add-undo-entry)))

               (rx/of (end-path-undo))))))))))

