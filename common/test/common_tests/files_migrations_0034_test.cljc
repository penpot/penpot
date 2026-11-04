;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files-migrations-0034-test
  (:require
   [app.common.files.migrations :as cfm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.types.modifiers :as ctm]
   [app.common.types.path :as path]
   [app.common.types.path.segment :as segment]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

;; 0034-fix-path-content-offset
;; A path whose drawing (:content) was moved apart from its box
;; (:selrect and :points) gets its content moved back onto the box.

(def ^:private migration "0034-fix-path-content-offset")

(defn- triangle
  [x0 y0]
  [{:command :move-to :params {:x x0 :y y0}}
   {:command :line-to :params {:x (+ x0 14) :y y0}}
   {:command :line-to :params {:x (+ x0 7) :y (+ y0 14)}}
   {:command :close-path}])

(defn- make-path
  [shape-id x0 y0]
  (cts/setup-shape {:id shape-id :type :path :content (triangle x0 y0)}))

(defn- rotate
  [shape angle]
  (gsh/transform-shape shape (ctm/rotation-modifiers shape (gsh/shape->center shape) angle)))

(defn- displace-content
  [shape delta]
  (update shape :content path/move-content delta))

(defn- migrate-shape
  [shape]
  (let [page-id (uuid/next)
        data    {:pages-index {page-id {:objects {(:id shape) shape}}}}
        data'   (cfm/migrate-data data migration)]
    (get-in data' [:pages-index page-id :objects (:id shape)])))

(defn- content-matches-box?
  [shape]
  (let [derived (path/update-geometry shape)]
    (and (every? true? (map gpt/close? (:points shape) (:points derived)))
         (every? #(mth/close? (get (:selrect shape) %) (get (:selrect derived) %))
                 [:x :y :width :height]))))

(t/deftest migration-0034-moves-displaced-content-onto-its-box
  (let [shape   (make-path (uuid/next) 1 1)
        broken  (displace-content shape (gpt/point 0 -182))
        shape'  (migrate-shape broken)]

    (t/is (not (content-matches-box? broken)) "the fixture is broken")
    (t/is (content-matches-box? shape') "content back onto the box")
    (t/is (= (:selrect broken) (:selrect shape')) "box unchanged")
    (t/is (= (:points broken) (:points shape')) "points unchanged")
    (t/is (= (segment/content->selrect (:content shape))
             (segment/content->selrect (:content shape')))
          "content where it was before being displaced")))

(t/deftest migration-0034-moves-displaced-content-of-a-rotated-path
  (let [shape   (rotate (make-path (uuid/next) 100 100) 30)
        broken  (displace-content shape (gpt/point 25 -40))
        shape'  (migrate-shape broken)]

    (t/is (not (gmt/close? (gmt/matrix) (:transform shape))) "the fixture is rotated")
    (t/is (not (content-matches-box? broken)) "the fixture is broken")
    (t/is (content-matches-box? shape') "content back onto the box")
    (t/is (= (:selrect broken) (:selrect shape')) "box unchanged")
    (t/is (= (:transform broken) (:transform shape')) "rotation unchanged")))

(t/deftest migration-0034-keeps-paths-whose-content-matches-the-box
  (let [shape  (make-path (uuid/next) 10 20)
        small  (displace-content shape (gpt/point 0.05 0))]

    (t/is (identical? shape (migrate-shape shape)) "consistent path untouched")
    (t/is (identical? small (migrate-shape small)) "float noise untouched")))

(t/deftest migration-0034-keeps-paths-whose-content-has-another-size
  (let [shape  (make-path (uuid/next) 0 0)
        bigger (assoc shape :content (path/content [{:command :move-to :params {:x 40 :y 0}}
                                                    {:command :line-to :params {:x 70 :y 0}}
                                                    {:command :line-to :params {:x 55 :y 30}}
                                                    {:command :close-path}]))]

    (t/is (identical? bigger (migrate-shape bigger)) "size mismatch untouched")))

(t/deftest migration-0034-ignores-shapes-other-than-paths
  (let [shape-id (uuid/next)
        rect     (cts/setup-shape {:id shape-id :type :rect :x 0 :y 0 :width 10 :height 10})]
    (t/is (identical? rect (migrate-shape rect)))))

(t/deftest migration-0034-fixes-paths-inside-components
  (let [shape        (make-path (uuid/next) 1 1)
        broken       (displace-content shape (gpt/point 0 109))
        component-id (uuid/next)
        data         {:pages-index {}
                      :components  {component-id {:id component-id
                                                  :deleted true
                                                  :objects {(:id broken) broken}}}}
        data'        (cfm/migrate-data data migration)
        shape'       (get-in data' [:components component-id :objects (:id broken)])]

    (t/is (content-matches-box? shape') "deleted component content fixed")))
