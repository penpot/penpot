;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.files.review-activity-test
  (:require
   [app.common.files.review-activity :as ra]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(def ^:private page-id (uuid/custom 1 1))
(def ^:private board-id (uuid/custom 2 1))
(def ^:private button-id (uuid/custom 2 2))
(def ^:private label-id (uuid/custom 2 3))

(defn- data
  [& objects]
  {:pages-index
   {page-id
    {:objects (into {uuid/zero {:id uuid/zero :type :frame :name "Root"}}
                    (map (juxt :id identity))
                    objects)}}})

(def ^:private board
  {:id board-id :type :frame :name "Checkout / Mobile"
   :parent-id uuid/zero :frame-id uuid/zero})

(def ^:private button
  {:id button-id :type :rect :name "Pay button" :r1 8 :height 48
   :parent-id board-id :frame-id board-id})

(def ^:private label
  {:id label-id :type :text :name "Label"
   :parent-id button-id :frame-id board-id})

(defn- save-entries
  "The entries of a save that turns `before` into `after` with `changes`."
  [before after changes]
  (let [touched (ra/touched changes)]
    (ra/entries (ra/collect-before before touched) after touched)))

(t/deftest modified-layer-keeps-the-values-before-and-after
  (let [after   (assoc button :r1 12 :height 52)
        entries (save-entries (data board button)
                              (data board after)
                              [{:type :mod-obj :page-id page-id :id button-id
                                :operations [{:type :set :attr :r1 :val 12}
                                             {:type :set :attr :height :val 52}]}])]
    (t/is (= [{:id button-id
               :page-id page-id
               :name "Pay button"
               :type :rect
               :frame-id board-id
               :frame-name "Checkout / Mobile"
               :kind :modified
               :attrs {:r1 [8 12] :height [48 52]}}]
             entries))))

(t/deftest added-group-gives-one-entry-for-its-top-layer
  (let [entries (save-entries (data board)
                              (data board button label)
                              [{:type :add-obj :page-id page-id :id button-id
                                :frame-id board-id :obj button}
                               {:type :add-obj :page-id page-id :id label-id
                                :frame-id board-id :obj label}])]
    (t/is (= [[button-id :added]]
             (mapv (juxt :id :kind) entries)))))

(t/deftest deleted-layer-keeps-its-name-and-board
  (let [entries (save-entries (data board button)
                              (data board)
                              [{:type :del-obj :page-id page-id :id button-id}])]
    (t/is (= [{:id button-id
               :page-id page-id
               :name "Pay button"
               :type :rect
               :frame-id board-id
               :frame-name "Checkout / Mobile"
               :kind :deleted}]
             entries))))

(t/deftest layer-added-and-deleted-in-one-save-is-no-change
  (let [entries (save-entries (data board)
                              (data board)
                              [{:type :add-obj :page-id page-id :id button-id
                                :frame-id board-id :obj button}
                               {:type :del-obj :page-id page-id :id button-id}])]
    (t/is (empty? entries))))

(t/deftest derived-only-change-is-not-an-entry
  (let [entries (save-entries (data board button)
                              (data board button)
                              [{:type :mod-obj :page-id page-id :id button-id
                                :operations [{:type :set :attr :position-data :val []}]}])]
    (t/is (empty? entries))))

(t/deftest component-changes-are-left-out
  (t/is (empty? (ra/touched [{:type :mod-obj :component-id (uuid/next) :id button-id
                              :operations [{:type :set :attr :r1 :val 4}]}]))))

(defn- row
  [profile-id ms entries]
  {:profile-id profile-id
   :created-at #?(:clj (java.time.Instant/ofEpochMilli ms)
                  :cljs (js/Date. ms))
   :entries entries})

(def ^:private ana (uuid/custom 9 1))
(def ^:private marc (uuid/custom 9 2))

(def ^:private modified-entry
  {:id button-id :page-id page-id :name "Pay button" :kind :modified})

(t/deftest net-entries-fold-the-values-across-saves
  (let [rows [(row ana 1000 [(assoc modified-entry :attrs {:r1 [8 10]})])
              (row marc 2000 [(assoc modified-entry :attrs {:r1 [10 12]})])]
        [entry & others] (ra/net-entries rows)]
    (t/is (empty? others))
    (t/is (= {:r1 [8 12]} (:attrs entry)))
    (t/is (= marc (:profile-id entry)))))

(t/deftest net-entries-drop-a-layer-added-and-later-deleted
  (let [rows [(row ana 1000 [{:id button-id :page-id page-id :kind :added}])
              (row ana 2000 [{:id button-id :page-id page-id :kind :deleted}])]]
    (t/is (empty? (ra/net-entries rows)))))

(t/deftest net-entries-drop-a-change-undone-later
  (let [rows [(row ana 1000 [(assoc modified-entry :attrs {:r1 [8 12]})])
              (row ana 2000 [(assoc modified-entry :attrs {:r1 [12 8]})])]]
    (t/is (empty? (ra/net-entries rows)))))

(t/deftest sessions-cut-by-person-and-by-gap
  (let [minute  (* 60 1000)
        rows    [(row ana 0 [(assoc modified-entry :attrs {:r1 [8 10]})])
                 (row ana (* 10 minute) [(assoc modified-entry :attrs {:r1 [10 12]})])
                 (row marc (* 20 minute) [(assoc modified-entry :attrs {:r1 [12 14]})])
                 (row marc (* 90 minute) [(assoc modified-entry :attrs {:r1 [14 16]})])]
        result  (ra/sessions rows)]
    (t/is (= [marc marc ana] (mapv :profile-id result)))
    (t/is (= {:r1 [8 12]} (-> result last :entries first :attrs)))))
