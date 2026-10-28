;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.branch-review-previews-test
  "How the compare previews rebuild the side the open file lacks: a
  deleted layer comes back from the diff, inside the board it was in,
  and a resized board draws at its own size."
  (:require
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.ui.workspace.sidebar.branch-review.previews :as brp]
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
  (let [main (brp/side-objects branch-objects items :main :branch)]
    (t/is (= icon (get main icon-id)))
    (t/is (contains? main path-id))
    (t/is (= [icon-id] (get-in main [board-id :shapes])))))

(t/deftest the-side-that-deleted-it-stays-without-it
  (let [branch (brp/side-objects branch-objects items :branch :branch)]
    (t/is (not (contains? branch icon-id)))
    (t/is (= [] (get-in branch [board-id :shapes])))))

(t/deftest a-resized-board-draws-at-its-own-size
  (let [main (brp/side-objects branch-objects items :main :branch)]
    (t/is (= 163 (get-in main [board-id :selrect :width])))
    (t/is (= 131 (get-in branch-objects [board-id :selrect :width])))))

(t/deftest a-layer-is-staged-in-the-nearest-board
  (let [main (brp/side-objects branch-objects items :main :branch)]
    (t/is (= board-id (brp/context-frame-id main path-id)))
    (t/is (= board-id (brp/context-frame-id main icon-id)))
    (t/is (nil? (brp/context-frame-id main board-id)))))
