;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.branch-review-sides-test
  "How the compare mode rebuilds the side the open file lacks: a deleted
  layer comes back from the diff, inside the board it was in, and a
  resized board takes its own size."
  (:require
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.ui.workspace.compare.common :as cc]
   [app.main.ui.workspace.compare.side :as side]
   [app.main.ui.workspace.sidebar.branch-review.sides :as brs]
   [cljs.test :as t :include-macros true]))

(def ^:private board-id (uuid/next))
(def ^:private icon-id (uuid/next))
(def ^:private path-id (uuid/next))

(def ^:private branch-objects
  "The branch, open: the button lost its icon and shrank."
  {uuid/zero {:id uuid/zero :type :frame :shapes [board-id]}
   board-id  (cts/setup-rect {:id board-id :type :frame :name "Button"
                              :parent-id uuid/zero :x 0 :y 0 :width 131 :height 48
                              :shapes []})})

(def ^:private icon
  {:id icon-id :type :group :name "icon" :parent-id board-id
   :x 119 :y 12 :width 24 :height 24 :shapes [path-id]})

(def ^:private items
  [{:kind :shape :status :deleted :id path-id
    :shape {:id path-id :type :path :name "path" :parent-id icon-id}}
   {:kind :shape :status :deleted :id icon-id :shape icon}
   {:kind :shape :status :modified :id board-id
    :changed-attrs {:width {:main 163 :branch 131}}}])

(t/deftest a-deleted-layer-comes-back-on-the-side-that-has-it
  (let [main (brs/side-objects branch-objects items :main :branch)]
    (t/is (= icon (get main icon-id)))
    (t/is (contains? main path-id))
    (t/is (= [icon-id] (get-in main [board-id :shapes])))))

(t/deftest the-side-that-deleted-it-stays-without-it
  (let [branch (brs/side-objects branch-objects items :branch :branch)]
    (t/is (not (contains? branch icon-id)))
    (t/is (= [] (get-in branch [board-id :shapes])))))

(t/deftest a-resized-board-draws-at-its-own-size
  (let [main (brs/side-objects branch-objects items :main :branch)]
    (t/is (= 163 (get-in main [board-id :selrect :width])))
    (t/is (= 131 (get-in branch-objects [board-id :selrect :width])))))

;; --- The changes an update brings

(def ^:private badge-id (uuid/next))

(def ^:private badge
  {:id badge-id :type :rect :name "badge" :parent-id board-id
   :x 4 :y 4 :width 8 :height 8})

(def ^:private incoming
  "What main did since the branch left: it widened the button, added a
  badge to it and deleted nothing the branch still has."
  [{:kind :shape :status :modified :id board-id :key "board"
    :changed-attrs {:width {:main 200 :branch 131}}}
   {:kind :shape :status :added :id badge-id :key "badge" :shape badge}])

(t/deftest an-update-brings-what-main-added-and-changed
  (let [after (brs/side-objects branch-objects incoming :main :branch :main)]
    (t/is (= 200 (get-in after [board-id :selrect :width])))
    (t/is (= badge (get after badge-id)))
    (t/is (= [badge-id] (get-in after [board-id :shapes])))))

(t/deftest an-update-takes-away-what-main-deleted
  (let [with-badge (-> branch-objects
                       (assoc badge-id badge)
                       (assoc-in [board-id :shapes] [badge-id]))
        after      (brs/side-objects with-badge
                                     [{:kind :shape :status :deleted :id badge-id}]
                                     :main :branch :main)]
    (t/is (not (contains? after badge-id)))
    (t/is (= [] (get-in after [board-id :shapes])))))

;; --- The choices made for the conflicts

(def ^:private clash
  {:kind :shape :status :conflict :id board-id
   :main   {:id board-id :type :frame :name "Main button" :parent-id uuid/zero
            :x 0 :y 0 :width 163 :height 48 :opacity 0.5 :shapes []}
   :branch (get branch-objects board-id)
   :changed-attrs {:width   {:main 163 :branch 131}
                   :opacity {:main 0.5 :branch nil}}})

(t/deftest a-conflict-shows-the-side-chosen-for-each-property
  (let [none  (brs/resolved-objects branch-objects [clash] {})
        mixed (brs/resolved-objects branch-objects [clash] {board-id {:width :branch :opacity :main}})
        whole (brs/resolved-objects branch-objects [clash] {board-id :main})
        gone  (brs/resolved-objects branch-objects [(assoc clash :main nil)] {board-id :main})]
    (t/is (identical? branch-objects none) "no choice: the branch as it is")
    (t/is (= 0.5 (get-in mixed [board-id :opacity])))
    (t/is (= 131 (get-in mixed [board-id :selrect :width])) "the branch keeps its width")
    (t/is (= "Main button" (get-in whole [board-id :name])))
    (t/is (not (contains? gone board-id)) "main deleted it")
    (t/is (identical? branch-objects
                      (brs/resolved-objects branch-objects [clash] {board-id :branch})))))

;; --- What the canvas shows in the compare mode

(def ^:private page-id (uuid/next))

(defn- comparison
  [opts]
  (merge {:mode {:hidden false}
          :phase :compare
          :incoming false
          :model {:items (mapv #(assoc % :page-id page-id) incoming)}
          :conflicts {:conflicts [(assoc clash :page-id page-id)]}
          :resolutions {}
          :excluded #{}}
         opts))

(t/deftest the-canvas-shows-the-branch-while-its-own-changes-are-listed
  (t/is (nil? (cc/canvas-objects (comparison {}) branch-objects page-id))))

(t/deftest the-canvas-shows-the-update-without-what-is-left-out
  (let [all  (cc/canvas-objects (comparison {:incoming true}) branch-objects page-id)
        some (cc/canvas-objects (comparison {:incoming true :excluded #{"badge"}})
                                branch-objects page-id)]
    (t/is (= :update (:kind all)))
    (t/is (contains? (:objects all) badge-id))
    (t/is (= 200 (get-in all [:objects board-id :selrect :width])))
    (t/is (not (contains? (:objects some) badge-id)) "a change left out is not brought in")
    (t/is (nil? (cc/canvas-objects (comparison {:incoming true}) branch-objects (uuid/next)))
          "another page has nothing of it")
    (t/is (nil? (cc/canvas-objects (comparison {:incoming true :mode {:hidden true}})
                                   branch-objects page-id))
          "hidden changes leave the branch as it is")))

(t/deftest the-canvas-shows-the-choices-and-the-side-tried-out
  (let [item    (assoc clash :page-id page-id)
        chosen  (cc/canvas-objects (comparison {:phase :resolve
                                                :resolutions {board-id {:opacity :main}}})
                                   branch-objects page-id)
        tried   (cc/canvas-objects (comparison {:phase :resolve
                                                :mode {:preview :main}
                                                :item (assoc item :main nil)})
                                   branch-objects page-id)]
    (t/is (= :result (:kind chosen)))
    (t/is (= 0.5 (get-in chosen [:objects board-id :opacity])))
    (t/is (nil? (cc/canvas-objects (comparison {:phase :resolve}) branch-objects page-id))
          "nothing chosen yet")
    (t/is (= :result (:kind tried)) "the side under the pointer counts as chosen")))

(t/deftest the-change-chosen-is-marked-alone
  (let [marks [{:key "a" :status :modified}
               {:key "b" :status :added}
               {:key "a/1" :status :usage}]]
    (t/is (= marks (cc/chosen-marks nil marks)) "none chosen: every mark")
    (t/is (= ["a" "a/1"] (mapv :key (cc/chosen-marks "a" marks)))
          "its own mark and the layers that use it")
    (t/is (= ["a/1"] (mapv :key (cc/chosen-marks "c" marks)))
          "a change of another page leaves no mark of the others")))

;; --- Main beside the branch

(defn- on-page
  [items]
  (mapv #(assoc % :page-id page-id) items))

(t/deftest main-is-put-together-from-either-direction
  (let [from-branch (cc/main-objects branch-objects page-id (on-page items) nil false)
        from-main   (cc/main-objects branch-objects page-id (on-page incoming) nil true)]
    (t/is (= icon (get from-branch icon-id)) "what the branch deleted is back")
    (t/is (= 163 (get-in from-branch [board-id :selrect :width])))
    (t/is (contains? from-main badge-id) "what main added is there")
    (t/is (= 200 (get-in from-main [board-id :selrect :width])))))

(t/deftest main-takes-the-changes-of-both-directions
  (let [own   (on-page (filterv #(= :deleted (:status %)) items))
        added (on-page (filterv #(= :added (:status %)) incoming))
        main  (cc/main-objects branch-objects page-id own added false)
        same  (cc/main-objects branch-objects page-id added own true)
        peek  (cc/canvas-objects (comparison {:mode {:peek true :other-changes added}
                                              :model {:items own}})
                                 branch-objects page-id)]
    (t/is (= icon (get main icon-id)) "what the branch deleted is back")
    (t/is (contains? main badge-id) "what main added since is there too")
    (t/is (and (contains? same icon-id) (contains? same badge-id))
          "whichever direction is listed")
    (t/is (= :main (:kind peek)))
    (t/is (= main (:objects peek)) "the canvas shows that main while peeking")
    (t/is (not (contains? (cc/main-objects branch-objects page-id own
                                           (mapv #(assoc % :page-id (uuid/next)) added)
                                           false)
                          badge-id))
          "a change of another page stays out")))

(t/deftest pictures-are-taken-of-the-boards-that-hold-changes
  (let [main  (cc/main-objects branch-objects page-id (on-page items) nil false)
        loose {:kind :shape :status :added :id (uuid/next)}]
    (t/is (= [board-id] (side/picture-roots items main branch-objects))
          "one picture of the board, whatever changed inside it")
    (t/is (= [] (side/picture-roots [loose] main branch-objects))
          "a layer neither side has in a board leaves nothing to draw")
    (t/is (= [] (side/picture-roots [{:kind :color :status :modified :id (uuid/next)}]
                                    main branch-objects)))))
