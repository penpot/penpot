;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.worker.index-test
  (:require
   [app.common.files.builder :as fb]
   [app.common.files.changes :as ch]
   [app.common.geom.rect :as grc]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.worker.impl :as impl]
   [app.worker.index :as idx]
   [cljs.test :as t :include-macros true]
   [clojure.set :as set]))

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

(t/deftest finalize-drops-only-that-page
  (let [page-a (make-page "Page A")
        page-b (make-page "Page B")]
    (initialize page-a)
    (initialize page-b)
    (impl/handler {:cmd :index/finalize :page-id (:id page-a)})
    (t/is (= {:pages     #{(:id page-b)}
              :selection #{(:id page-b)}
              :snap      #{(:id page-b)}}
             (indexed-ids @idx/state)))))

(t/deftest finalize-unknown-page-is-noop
  (let [page (make-page "Page 1")]
    (initialize page)
    (let [before @idx/state]
      (impl/handler {:cmd :index/finalize :page-id (uuid/next)})
      (t/is (= before @idx/state)))))

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

(defn- get-stored-shape
  [page-id shape-id]
  (get-in @idx/state [:pages-index page-id :objects shape-id]))

(t/deftest initialize-stores-a-trimmed-page
  (let [page     (make-page "Page 1")
        shape-id (-> page :objects (dissoc uuid/zero) keys first)]
    (initialize page)
    (t/is (some? (get-stored-shape (:id page) shape-id)))
    (t/is (nil? (:name (get-stored-shape (:id page) shape-id))))))

(t/deftest update-stores-trimmed-fills
  (let [page     (make-page "Page 1")
        page-id  (:id page)
        shape-id (-> page :objects (dissoc uuid/zero) keys first)]
    (initialize page)
    (update-index page-id [{:type :mod-obj :id shape-id :page-id page-id
                            :operations [{:type :set :attr :fills
                                          :val [{:fill-color "#ff0000" :fill-opacity 1}]}]}])
    (t/is (= [{}] (:fills (get-stored-shape page-id shape-id))))))

(t/deftest update-renaming-a-shape-keeps-the-indexes
  (let [page     (make-page "Page 1")
        page-id  (:id page)
        shape-id (-> page :objects (dissoc uuid/zero) keys first)]
    (initialize page)
    (let [before @idx/state]
      (update-index page-id [{:type :mod-obj :id shape-id :page-id page-id
                              :operations [{:type :set :attr :name :val "renamed"}]}])
      (t/is (identical? (::idx/selection before) (::idx/selection @idx/state)))
      (t/is (identical? (::idx/snap before) (::idx/snap @idx/state))))))

(t/deftest update-skips-changes-the-indexes-do-not-read
  (let [page     (make-page "Page 1")
        page-id  (:id page)
        shape-id (-> page :objects (dissoc uuid/zero) keys first)]
    (initialize page)
    (let [before (get-in @idx/state [:pages-index page-id])]
      (update-index page-id [{:type :set-plugin-data :object-type :shape
                              :object-id shape-id :page-id page-id
                              :namespace "ns" :key "k" :value "v"}
                             {:type :mod-page :id page-id :page-id page-id :name "Renamed"}])
      (t/is (= before (get-in @idx/state [:pages-index page-id]))))))

(t/deftest every-change-type-is-replayed-or-skipped
  (let [known (set/union idx/index-change-types idx/skipped-change-types)]
    (t/is (= #{} (set/difference (set (keys (methods ch/process-change))) known))
          "Add the new change type to one set; replaying it may need attributes in `app.worker.trim`")
    (t/is (= #{} (set/intersection idx/index-change-types idx/skipped-change-types)))))

(t/deftest update-skips-unknown-change-types
  (let [page     (make-page "Page 1")
        page-id  (:id page)
        shape-id (-> page :objects (dissoc uuid/zero) keys first)]
    (initialize page)
    (let [before (get-in @idx/state [:pages-index page-id])]
      (update-index page-id [{:type :unknown-change :id shape-id :page-id page-id}])
      (t/is (= before (get-in @idx/state [:pages-index page-id]))))))
