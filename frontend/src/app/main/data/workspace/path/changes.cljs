;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.path.changes
  (:require
   [app.common.pages :as cp]
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
  (let [shape-id (:id shape)
        frame-id (:frame-id shape)
        parent-id (:parent-id shape)
        parent-index (cp/position-on-parent shape-id objects)

        [old-points old-selrect] (helpers/content->points+selrect shape old-content)
        [new-points new-selrect] (helpers/content->points+selrect shape new-content)

        rch (if (empty? new-content)
              [{:type :del-obj
                :id shape-id
                :page-id page-id}
               {:type :reg-objects
                :page-id page-id
                :shapes [shape-id]}]

              [{:type :mod-obj
                :id shape-id
                :page-id page-id
                :operations [{:type :set :attr :content :val new-content}
                             {:type :set :attr :selrect :val new-selrect}
                             {:type :set :attr :points  :val new-points}]}
               {:type :reg-objects
                :page-id page-id
                :shapes [shape-id]}])

        uch (if (empty? new-content)
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
       (let [content (get-in state (st/get-path state :content))
             content (if (and (not preserve-move-to)
                              (= (-> content last :command) :move-to))
                       (into [] (take (dec (count content)) content))
                       content)]
         (assoc-in state (st/get-path state :content) content)))

     ptk/WatchEvent
     (watch [_ state stream]
       (let [objects (wsh/lookup-page-objects state)
             id (get-in state [:workspace-local :edition])
             old-content (get-in state [:workspace-local :edit-path id :old-content])]
         (if (some? old-content)
           (let [shape (get-in state (st/get-path state))
                 page-id (:current-page-id state)
                 [rch uch] (generate-path-changes objects page-id shape old-content (:content shape))]
             (rx/of (dch/commit-changes rch uch {:commit-local? true})))
           (rx/empty)))))))


