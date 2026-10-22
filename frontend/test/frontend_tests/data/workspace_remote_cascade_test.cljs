;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-remote-cascade-test
  "An update of the main of a library component, made from a copy in the
  file: the library components that nest it change too, and their copies
  in the file follow."
  (:require
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.undo :as dwu]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.composable-tests.interpreter :as ftm]
   [frontend-tests.helpers.mock :as mock]))

(t/use-fixtures :each ftm/fixture)

(def ^:private old-color "#111111")
(def ^:private new-color "#222222")

(defn- install!
  "A library with a component :ca and a component :cb whose main nests a
   copy of :ca, and a file with a copy of each."
  []
  (let [library (-> (cthf/sample-file :library :is-shared true)
                    (ctho/add-nested-component :ca :main-a :main-a-child
                                               :cb :main-b :nested-a
                                               :main1-child-params
                                               {:fills (cths/sample-fills-color :fill-color old-color)}))
        file    (-> (cthf/sample-file :file1)
                    (cthc/instantiate-component :ca :copy-a
                                                :library library
                                                :children-labels [:copy-a-child])
                    (cthc/instantiate-component :cb :copy-b
                                                :library library
                                                :children-labels [:copy-b-nested :copy-b-nested-child]))]
    (ftm/install! (-> (tm/make-situation file)
                      (tm/with-aux-files {(:id library) library})))
    library))

(defn- fill-of
  [file label]
  (-> (cths/get-shape file label) :fills first :fill-color))

(defn- nested-child-fill
  "The fill of the child of the copy of :ca that the main of :cb nests."
  [library]
  (let [objects (:objects (cthf/current-page library))
        nested  (cths/get-shape library :nested-a)]
    (-> (get objects (first (:shapes nested))) :fills first :fill-color)))

(t/deftest ^:async an-update-of-a-library-main-reaches-the-copies-of-the-components-that-nest-it
  (await
   (mock/with-mocks*
     {rp/cmd!  (mock/stub (fn [& _] (rx/of nil)))
      rx/timer mock/timer-mock}
     (let [library (install!)]
       (await (ftm/await-step [(ftm/fill-commit :copy-a-child new-color {})]))
       (await (ftm/await-step [(dwl/update-component-sync (cthi/id :copy-a) (:id library))]))
       (let [library' (get-in @st/state [:files (:id library)])]
         (t/is (= new-color (fill-of library' :main-a-child)))
         (t/is (= new-color (nested-child-fill library'))))
       (t/is (= new-color (fill-of (ftm/current-file) :copy-a-child)))
       (t/is (= new-color (fill-of (ftm/current-file) :copy-b-nested-child)))))))

(t/deftest ^:async undo-and-redo-update-main-restore-both-files
  (await
   (mock/with-mocks*
     {rp/cmd! (mock/stub (fn [& _] (rx/of nil)))
      rx/timer mock/timer-mock}
     (let [library (install!)]
       (await (ftm/await-step [(ftm/fill-commit :copy-a-child new-color {})]))
       (let [before (ftm/snapshot)]
         (await (ftm/await-step [(dwl/update-component-sync (cthi/id :copy-a) (:id library))]))
         (let [after (ftm/snapshot)]
           (await (ftm/await-step [dwu/undo]))
           (t/is (= before (ftm/snapshot)))
           (await (ftm/await-step [dwu/redo]))
           (t/is (= after (ftm/snapshot)))))
       (await (ftm/await-step [dwu/undo]))
       (let [restored (get-in @st/state [:files (:id library)])]
         (t/is (= old-color (fill-of restored :main-a-child)))
         (t/is (= old-color (nested-child-fill restored))))
       (t/is (= old-color (fill-of (ftm/current-file) :copy-b-nested-child)))
       (t/is (= new-color (fill-of (ftm/current-file) :copy-a-child)))
       (await (ftm/await-step [dwu/redo]))
       (let [redone (get-in @st/state [:files (:id library)])]
         (t/is (= new-color (fill-of redone :main-a-child)))
         (t/is (= new-color (nested-child-fill redone))))
       (t/is (= new-color (fill-of (ftm/current-file) :copy-b-nested-child)))))))
