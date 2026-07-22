;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL


(ns frontend-tests.logic.path-test-helpers
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.path :as path]
   [app.main.data.workspace.edition :as dwe]
   [app.main.data.workspace.path.edition :as path.edition]
   [app.main.data.workspace.path.state :as path.state]))

(defn setup-rect-file
  []
  (ctho/add-rect (cthf/sample-file :file1)
                 :rect1
                 :x 10
                 :y 20
                 :width 100
                 :height 80))

(defn start-path-edition-events
  [id]
  [(dwe/start-edition-mode id)
   (path.edition/start-path-edit id)])

(defn move-drawing-content
  [delta]
  (fn [state]
    (path.state/set-content
     state
     (path/move-content (path.state/get-path state :content) delta))))

(defn drawing-path-state
  []
  (let [file  (setup-rect-file)
        shape (-> (cths/get-shape file :rect1)
                  (path/convert-to-path))]
    {:workspace-drawing {:object shape}}))

(defn selectable-path-content
  []
  (path/content
   [{:command :move-to
     :params {:x 0 :y 0}}
    {:command :curve-to
     :params {:c1x 2 :c1y 0
              :c2x 8 :c2y 0
              :x 10 :y 0}}
    {:command :curve-to
     :params {:c1x 12 :c1y 0
              :c2x 18 :c2y 0
              :x 20 :y 0}}]))

(defn selectable-path-state
  [id content selection]
  {:workspace-local {:edition id
                     :edit-path {id {:selection selection}}}
   :workspace-drawing {:object {:id id
                                :type :path
                                :content content}}})

(defn mixed-corner-curve-content
  []
  (path/content
   [{:command :move-to
     :params {:x 0 :y 0}}
    {:command :line-to
     :params {:x 10 :y 0}}
    {:command :curve-to
     :params {:c1x 12 :c1y 4
              :c2x 18 :c2y 4
              :x 20 :y 0}}]))

(defn corner-path-content
  "Returns selectable content with a corner at `(10, 0)`."
  []
  (path/content
   [{:command :move-to
     :params {:x 0 :y 0}}
    {:command :curve-to
     :params {:c1x 2 :c1y 0
              :c2x 8 :c2y 0
              :x 10 :y 0}}
    {:command :curve-to
     :params {:c1x 12 :c1y 6
              :c2x 18 :c2y 0
              :x 20 :y 0}}]))

