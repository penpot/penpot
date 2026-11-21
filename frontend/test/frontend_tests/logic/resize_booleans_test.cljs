;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL
(ns frontend-tests.logic.resize-booleans-test
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.path :as path]
   [app.main.data.workspace.modifiers :as dwm]
   [app.main.data.workspace.transforms :as dwtr]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]))

(t/use-fixtures :each
  {:before (fn []
             (thp/reset-idmap!)
             (cthi/reset-idmap!))})

(defn- with-bool-content
  "`file` with the content of the boolean `label` calculated from its
  children."
  [file label]
  (let [page    (cthf/current-page file)
        objects (:objects page)
        id      (cthi/id label)]
    (assoc-in file [:data :pages-index (:id page) :objects id]
              (path/update-bool-shape (get objects id) objects))))

(defn- row-with-icon
  "A flex row that hugs a rect and an icon, a boolean of two rects."
  []
  (-> (cthf/sample-file :file1)
      (cths/add-sample-shape :row
                             :type :frame :layout :flex :layout-flex-dir :row
                             :layout-item-h-sizing :auto :layout-item-v-sizing :auto
                             :layout-gap {:row-gap 10 :column-gap 10}
                             :layout-padding-type :multiple
                             :layout-padding {:p1 8 :p2 8 :p3 8 :p4 8})
      (cths/add-sample-shape :grow :parent-label :row :width 50 :height 20)
      (cths/add-sample-shape :icon :parent-label :row :type :bool :bool-type :union)
      (cths/add-sample-shape :part-a :parent-label :icon :x 0 :y 0 :width 10 :height 10)
      (cths/add-sample-shape :part-b :parent-label :icon :x 5 :y 5 :width 10 :height 10)
      (with-bool-content :icon)))

(defn- resize
  "Run a resize of `label` to `width` without touched flags, counting the
  boolean calculations, and call `check-fn` with the resulting file and the
  count."
  [file label width done check-fn]
  (let [store  (ths/setup-store file)
        count* (atom 0)
        prev   path/wasm:calc-bool-content]
    (set! path/wasm:calc-bool-content
          (fn [shape objects]
            (swap! count* inc)
            (path/calc-bool-content shape objects)))
    (ths/run-store
     store
     (fn []
       (set! path/wasm:calc-bool-content prev)
       (done))
     [(dwtr/update-dimensions [(cthi/id label)] :width width
                              {:ignore-touched true :no-wasm? true})]
     (fn [new-state]
       (check-fn (ths/get-file-from-state new-state) @count*)))))

(defn- content-matches-children?
  "True when the boolean `label` of `file` holds the content of its
  children."
  [file label]
  (let [objects (:objects (cthf/current-page file))
        icon    (get objects (cthi/id label))]
    (grc/close-rect? (:selrect icon)
                     (:selrect (path/update-bool-shape icon objects)))))

(t/deftest moved-boolean-keeps-its-content
  (t/async
    done
    (let [file (row-with-icon)]
      (resize file :grow 120 done
              (fn [file' calculations]
                (t/is (zero? calculations))
                (t/is (not= (get-in (cths/get-shape file :icon) [:selrect :x])
                            (get-in (cths/get-shape file' :icon) [:selrect :x])))
                (t/is (content-matches-children? file' :icon)))))))

(t/deftest boolean-with-a-resized-child-is-calculated
  (t/async
    done
    (let [file (row-with-icon)]
      (resize file :part-a 30 done
              (fn [file' calculations]
                (t/is (pos? calculations))
                (t/is (content-matches-children? file' :icon)))))))

(t/deftest a-boolean-keeps-its-content-only-when-it-moves
  (let [objects (:objects (cthf/current-page (row-with-icon)))
        ids     (mapv cthi/id [:icon :part-a :part-b])
        rigid   #(#'dwm/rigid-bool-ids objects (zipmap ids (repeat %)) ids)]
    (t/is (= #{(cthi/id :icon)} (rigid (gmt/translate-matrix (gpt/point 10 5)))))
    (t/is (empty? (rigid (gmt/scale-matrix (gpt/point 2 1))))
          "a resize scales the content, not the radii of the shapes in it")))
