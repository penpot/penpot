;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.path.changes
  (:require
   [app.common.pages.helpers :as cph]
   [app.common.spec :as us]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.path.helpers :as helpers]
   [app.main.data.workspace.path.spec :as spec]
   [app.main.data.workspace.path.state :as st]
   [app.main.data.workspace.state-helpers :as wsh]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(defn generate-path-changes
  "Generates content changes and the undos for the content given"
  [objects page-id shape old-content new-content]
  (us/verify ::spec/content old-content)
  (us/verify ::spec/content new-content)
  (let [shape-id     (:id shape)
        frame-id     (:frame-id shape)
        parent-id    (:parent-id shape)
        parent-index (cph/get-position-on-parent objects shape-id)

        [old-points old-selrect] (helpers/content->points+selrect shape old-content)
        [new-points new-selrect] (helpers/content->points+selrect shape new-content)

        rch (cond
              ;; https://tree.taiga.io/project/penpot/issue/2366
              (nil? shape-id)
              []

              (empty? new-content)
              [{:type :del-obj
                :id shape-id
                :page-id page-id}
               {:type :reg-objects
                :page-id page-id
                :shapes [shape-id]}]

              :else
              [{:type :mod-obj
                :id shape-id
                :page-id page-id
                :operations [{:type :set :attr :content :val new-content}
                             {:type :set :attr :selrect :val new-selrect}
                             {:type :set :attr :points  :val new-points}]}
               {:type :reg-objects
                :page-id page-id
                :shapes [shape-id]}])

        uch (cond
              ;; https://tree.taiga.io/project/penpot/issue/2366
              (nil? shape-id)
              []

              (empty? new-content)
              [{:type :add-obj
                :id shape-id
                :obj shape
                :page-id page-id
                :frame-id frame-id
                :parent-id parent-id
                :index parent-index}
               {:type :reg-objects
                :page-id page-id
                :shapes [shape-id]}]

              :else
              [{:type :mod-obj
                :id shape-id
                :page-id page-id
                :operations [{:type :set :attr :content :val old-content}
                             {:type :set :attr :selrect :val old-selrect}
                             {:type :set :attr :points  :val old-points}]}
               {:type :reg-objects
                :page-id page-id
                :shapes [shape-id]}])]
    [rch uch]))

(defn save-path-content
  ([]
   (save-path-content {}))
  ([{:keys [preserve-move-to] :or {preserve-move-to false}}]
   (ptk/reify ::save-path-content
     ptk/UpdateEvent
     (update [_ state]
       (let [content (st/get-path state :content)
             content (if (and (not preserve-move-to)
                              (= (-> content last :command) :move-to))
                       (into [] (take (dec (count content)) content))
                       content)]
         (-> state
             (st/set-content content))))

     ptk/WatchEvent
     (watch [it state _]
       (let [objects     (wsh/lookup-page-objects state)
             page-id     (:current-page-id state)
             id          (get-in state [:workspace-local :edition])
             old-content (get-in state [:workspace-local :edit-path id :old-content])
             shape       (st/get-path state)]
         (if (and (some? old-content) (some? (:id shape)))
           (let [[rch uch] (generate-path-changes objects page-id shape old-content (:content shape))]
             (rx/of (dch/commit-changes {:redo-changes rch
                                         :undo-changes uch
                                         :origin it})))
           (rx/empty)))))))


