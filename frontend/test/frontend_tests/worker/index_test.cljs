;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.worker.index-test
  (:require
   [app.common.files.builder :as fb]
   [app.common.geom.rect :as grc]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.worker.impl :as impl]
   [app.worker.index :as idx]
   [cljs.test :as t :include-macros true]))

(t/use-fixtures :each
  {:before #(reset! idx/state {:pages-index {}})})

(defn- make-page
  [name]
  (-> (fb/create-state)
      (fb/add-file {:name "Test"})
      (fb/add-page {:name name})
      (fb/add-shape {:type :rect :x 0 :y 0 :width 100 :height 100})
      (fb/get-current-page)))

(defn- initialize
  [page]
  (impl/handler {:cmd :index/initialize :page page}))

(defn- update-index
  [page-id changes]
  (impl/handler {:cmd :index/update :page-id page-id :changes changes}))

(defn- indexed-ids
  [state]
  {:pages     (set (keys (:pages-index state)))
   :selection (set (keys (::idx/selection state)))
   :snap      (set (keys (::idx/snap state)))})

(defn- add-rect-change
  [page-id shape-id]
  (let [shape (cts/setup-shape {:id shape-id :type :rect
                                :x 200 :y 200 :width 50 :height 50})]
    {:type :add-obj
     :id shape-id
     :page-id page-id
     :parent-id uuid/zero
     :frame-id uuid/zero
     :obj shape}))

(t/deftest update-ignores-pages-not-indexed
  (let [page    (make-page "Page 1")
        page-id (uuid/next)]
    (initialize page)
    (update-index page-id [(add-rect-change page-id (uuid/next))])
    (t/is (= {:pages     #{(:id page)}
              :selection #{(:id page)}
              :snap      #{(:id page)}}
             (indexed-ids @idx/state)))))

(t/deftest update-drops-indexes-of-deleted-page
  (let [page    (make-page "Page 1")
        page-id (:id page)]
    (initialize page)
    (update-index page-id [{:type :del-page :id page-id :page-id page-id}])
    (t/is (= {:pages #{} :selection #{} :snap #{}}
             (indexed-ids @idx/state)))))

(t/deftest update-indexes-new-shapes-of-indexed-page
  (let [page     (make-page "Page 1")
        page-id  (:id page)
        shape-id (uuid/next)]
    (initialize page)
    (update-index page-id [(add-rect-change page-id shape-id)])
    (t/is (contains? (get-in @idx/state [:pages-index page-id :objects]) shape-id))
    (t/is (contains? (set (impl/handler {:cmd :index/query-selection
                                         :page-id page-id
                                         :rect (grc/make-rect 190 190 70 70)}))
                     shape-id))))
