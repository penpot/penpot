;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.large-sync-undo-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.geom.point :as gpt]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.pages-list :as ctpl]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- add-copies
  "`file` with `n` copies of the component `component-label` at the root of
   its page, added in one step (the copies of a large design system)."
  [file component-label n]
  (let [page      (thf/current-page file)
        component (thc/get-component file component-label)
        copies    (map (fn [_]
                         (second (ctn/make-component-instance page component (:data file)
                                                              (gpt/point 100 100) {})))
                       (range n))]
    (ctf/update-file-data
     file
     (fn [data]
       (ctpl/update-page data (:id page)
                         (fn [page]
                           (-> page
                               (update :objects into (comp cat (map (juxt :id identity))) copies)
                               (update-in [:objects uuid/zero :shapes] into (map (comp :id first) copies)))))))))

(defn- read-count
  "How many changes `changes` holds, or `:stack-overflow` when reading them
   overflows the stack."
  [changes]
  (try
    (count changes)
    (catch #?(:clj StackOverflowError :cljs js/RangeError) _
      :stack-overflow)))

(t/deftest undo-of-a-sync-into-many-copies-can-be-read
  ;; A large sync builds a long undo list; reading it must not recurse once
  ;; per synced shape (F84).
  (let [file    (-> (thf/sample-file :file1)
                    (tho/add-simple-component :component1 :main-root :main-child)
                    (add-copies :component1 5000))
        page    (thf/current-page file)
        edit    (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                            #{(thi/id :main-child)}
                                            #(assoc % :fills (ths/sample-fills-color :fill-color "#ff0000"))
                                            (:objects page)
                                            {})
        file    (thf/apply-changes file edit :validate? false)
        sync    (cll/generate-sync-file-changes (pcb/empty-changes) nil :components (:id file)
                                                (thi/id :component1) (:id file) {(:id file) file} (:id file))]
    (t/is (= 10000 (count (:redo-changes sync))))
    (t/is (= 10000 (read-count (:undo-changes sync))))))
