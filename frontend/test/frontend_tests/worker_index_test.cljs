;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.worker-index-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.worker.impl :as impl]
   [app.worker.index :as index]
   [cljs.test :as t :include-macros true]))

(t/use-fixtures :each
  {:before cthi/reset-idmap!})

(t/deftest update-index-with-validate-shapes-changes
  ;; The index only holds pages, without the file id nor its components, so
  ;; a :validate-shapes change must not stop it from applying the others
  (let [;; ==== Setup
        file    (-> (cthf/sample-file :file1)
                    (ctho/add-simple-component :c01 :m01 :r01))
        page    (cthf/current-page file)
        main    (cths/get-shape file :m01)
        rect    (cts/setup-shape {:id (uuid/next) :type :rect :name "Rect"
                                  :x 0 :y 0 :width 10 :height 10})

        changes (-> (pcb/empty-changes nil (:id page))
                    (pcb/with-objects (:objects page))
                    (pcb/add-object rect)
                    (pcb/validate-shapes (:id page) [(:id main)] "test"))

        ;; ==== Action
        _       (impl/handler {:cmd :index/initialize :page page})
        _       (impl/handler {:cmd :index/update
                               :page-id (:id page)
                               :changes (:redo-changes changes)})]

    ;; ==== Check
    (t/is (some? (get-in @index/state [:pages-index (:id page) :objects (:id rect)])))))
